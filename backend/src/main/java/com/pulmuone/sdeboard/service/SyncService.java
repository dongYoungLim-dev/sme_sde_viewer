package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulmuone.sdeboard.adapter.itsm.ItsmClient;
import com.pulmuone.sdeboard.adapter.itsm.ItsmResponseMapper;
import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.*;
import com.pulmuone.sdeboard.repo.*;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ITSM 폴링 동기화 — **로그인한 사용자 세션 단위**로 돈다.
 *
 * 결정 사항 반영:
 *  · 서비스 계정 전역 폴링 제거. 로그인 세션이 없으면 아무 것도 조회하지 않는다(= 밤사이 수집 없음).
 *  · ITSM accessToken 은 30분 → 만료가 임박하면 세션 메모리의 자격증명으로 재로그인한다.
 *    (itsm.session.keep-credential=false 면 재로그인하지 않고 재인증을 요구한다)
 *  · 인증 실패 시 **재시도하지 않고** 그 세션의 폴링을 멈춘다 — 계정 잠금 방지.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final ItsmProperties props;
    private final ItsmClient client;
    private final ItsmResponseMapper mapper;
    private final ItsmRequestRepository reqRepo;
    private final RequestOwnerRepository ownerRepo;
    private final RequestStatusHistoryRepository histRepo;
    private final SyncLogRepository syncLogRepo;
    private final SessionRegistry sessions;
    private final AppUserRepository userRepo;

    /** accessToken(30분)이 이만큼 지나면 미리 재로그인 */
    private static final Duration TOKEN_REFRESH_AFTER = Duration.ofMinutes(25);

    /** 1분마다 깨어나 "동기화할 때가 된" 세션만 돌린다. 세션이 없으면 즉시 종료. */
    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void scheduled() {
        if (props.isMock() || !props.getPoll().isEnabled()) return;
        long intervalMs = props.getPoll().getIntervalMs();
        for (UserSession s : sessions.active()) {
            LocalDateTime last = s.getLastSyncAt();
            boolean due = last == null || last.plusNanos(intervalMs * 1_000_000L).isBefore(AppTime.now());
            if (!due) continue;
            if ("AUTH_FAILED".equals(s.getSyncState())) continue;   // 재인증 전까지 건드리지 않는다
            try {
                syncSession(s);
            } catch (Exception e) {
                log.warn("세션 동기화 실패 user={} : {}", s.getLoginId(), e.getMessage());
            }
        }
    }

    /** 한 사용자의 ITSM To-Do 를 미러링한다. */
    @Transactional
    public SyncLog syncSession(UserSession session) {
        SyncLog logRow = new SyncLog();
        logRow.setUserId(session.getUserId());
        logRow.setStartedAt(AppTime.now());
        int fetched = 0, upserted = 0, changed = 0;
        try {
            String token = ensureToken(session);

            List<JsonNode> items = fetchAllPages(token);
            fetched = items.size();

            LocalDateTime now = AppTime.now();
            Set<String> seen = new LinkedHashSet<>();

            for (JsonNode n : items) {
                String reqNo = n.path("reqNo").asText(null);
                if (reqNo == null || reqNo.isBlank()) continue;
                seen.add(reqNo);

                ItsmRequest existing = reqRepo.findByReqNo(reqNo).orElse(null);
                String oldStatus   = existing == null ? null : existing.getWorkStatus();
                String oldStaNm    = existing == null ? null : existing.getItsmStaNm();
                String oldAssignee = existing == null ? null : existing.getAssigneeName();

                ItsmRequest target = mapper.toEntity(n, existing);
                target.setSyncedAt(now);
                // 추적불가였던 건이 다시 누군가의 To-Do 에 나타났다 — 스케줄 없이 사라졌던 것이므로 대기로 돌아간다.
                if (BoardStatus.UNTRACKED.equals(target.getWorkStatus())) target.setWorkStatus(BoardStatus.WAITING);

                boolean statusChanged   = existing != null && !Objects.equals(oldStatus, target.getWorkStatus());
                boolean staNmChanged    = existing != null && !Objects.equals(oldStaNm, target.getItsmStaNm());
                boolean assigneeChanged = existing != null && !Objects.equals(oldAssignee, target.getAssigneeName());
                if (existing == null || statusChanged || staNmChanged || assigneeChanged) target.setLastChangedAt(now);
                // 완료를 **처음 본** 시각을 박아 둔다. ITSM 이 완료 시각을 주지 않아 이것이 유일한 근거다.
                // 한 번 채워지면 다시 쓰지 않는다(상태가 되돌아가도 최초 관측이 기준).
                if (BoardStatus.DONE.equals(target.getWorkStatus()) && target.getDoneAt() == null) target.setDoneAt(now);
                reqRepo.save(target);
                upserted++;

                // 처음 본 건은 '최초 관측' 이력을 한 줄 남긴다.
                //  — 없으면 상세 화면의 진행 단계에서 최초 상태가 '건너뜀'으로 잘못 보인다.
                if (existing == null) {
                    RequestStatusHistory h = new RequestStatusHistory();
                    h.setReqNo(reqNo);
                    h.setToStatus(target.getWorkStatus());
                    h.setToStaNm(target.getItsmStaNm());
                    h.setToAssignee(target.getAssigneeName());
                    h.setActorPerId(target.getAssigneePerId());
                    h.setActorName(target.getAssigneeName());
                    h.setObservedAt(now);
                    histRepo.save(h);
                } else if (statusChanged || staNmChanged || assigneeChanged) {
                    RequestStatusHistory h = new RequestStatusHistory();
                    h.setReqNo(reqNo);
                    h.setFromStatus(oldStatus);      h.setToStatus(target.getWorkStatus());
                    h.setFromStaNm(oldStaNm);        h.setToStaNm(target.getItsmStaNm());
                    h.setFromAssignee(oldAssignee);  h.setToAssignee(target.getAssigneeName());
                    h.setActorPerId(target.getAssigneePerId());
                    h.setActorName(target.getAssigneeName());
                    h.setObservedAt(now);
                    histRepo.save(h);
                    changed++;
                }
                upsertOwner(session.getUserId(), reqNo, now);
            }
            deactivateMissingOwners(session.getUserId(), seen, now);

            session.setLastSyncAt(now);
            session.setSyncState("OK");
            session.setSyncMessage(null);
            markUser(session.getUserId(), now, "LINKED");
            logRow.setResult("SUCCESS");
            log.info("동기화 완료 user={} fetched={} upserted={} changed={}",
                    session.getLoginId(), fetched, upserted, changed);
        } catch (AuthFailedException e) {
            // 자격증명 문제 — 재시도하지 않는다(계정 잠금 방지)
            session.setSyncState("AUTH_FAILED");
            session.setSyncMessage(e.getMessage());
            session.wipe();
            markUser(session.getUserId(), session.getLastSyncAt(), "AUTH_FAILED");
            logRow.setResult("FAIL");
            logRow.setErrorMsg(e.getMessage());
            log.warn("ITSM 인증 실패 — 폴링 중단 user={} : {}", session.getLoginId(), e.getMessage());
        } catch (Exception e) {
            session.setSyncState("ERROR");
            session.setSyncMessage(e.getMessage());
            logRow.setResult("FAIL");
            logRow.setErrorMsg(e.getMessage());
            log.warn("동기화 실패 user={} : {}", session.getLoginId(), e.getMessage());
        }
        logRow.setFetchedCnt(fetched);
        logRow.setUpsertedCnt(upserted);
        logRow.setChangedCnt(changed);
        logRow.setFinishedAt(AppTime.now());
        return syncLogRepo.save(logRow);
    }

    /** 사용자 마스터에 마지막 동기화 시각·연동 상태를 남긴다. */
    private void markUser(Long userId, LocalDateTime at, String linkStatus) {
        userRepo.findById(userId).ifPresent(u -> {
            if (at != null) u.setLastSyncAt(at);
            if (linkStatus != null) u.setLinkStatus(linkStatus);
            userRepo.save(u);
        });
    }

    /** 토큰이 살아있으면 그대로, 만료 임박이면 세션 자격증명으로 재로그인. */
    private String ensureToken(UserSession s) {
        boolean stale = s.getTokenIssuedAt() == null
                || s.getTokenIssuedAt().plus(TOKEN_REFRESH_AFTER).isBefore(AppTime.now())
                || s.getItsmAccessToken() == null;
        if (!stale) return s.getItsmAccessToken();

        String credential = s.credential();
        if (credential == null) {
            throw new AuthFailedException("ITSM 토큰(30분)이 만료되었습니다. 다시 로그인하세요.");
        }
        ItsmClient.LoginResult r = client.login(s.getLoginId(), credential);
        if (!r.success()) {
            if (r.authFailed()) throw new AuthFailedException("ITSM 재인증 실패 — 비밀번호가 변경되었을 수 있습니다.");
            throw new IllegalStateException("ITSM 재로그인 실패: " + r.message());
        }
        s.setItsmAccessToken(r.accessToken());
        s.setItsmRefreshToken(r.refreshToken());
        s.setTokenIssuedAt(AppTime.now());
        return r.accessToken();
    }

    private void upsertOwner(Long userId, String reqNo, LocalDateTime now) {
        RequestOwner o = ownerRepo.findByUserIdAndReqNo(userId, reqNo).orElseGet(() -> {
            RequestOwner n = new RequestOwner();
            n.setUserId(userId);
            n.setReqNo(reqNo);
            n.setFirstSeen(now);
            return n;
        });
        o.setLastSeen(now);
        o.setActive(true);
        ownerRepo.save(o);
    }

    /**
     * 이번 조회에 안 나온 건 = 그 사용자의 ITSM To-Do 에서 내려간 것 → 소유 매핑만 비활성.
     *
     * ⚠️ **미러 자체는 지우지도 감추지도 않는다.** ITSM 에서 '변경완료 확인'을 누르면 To-Do 목록에서
     * 사라지는데, 예전에는 그 순간 우리 화면에서도 통째로 없어졌다(DB엔 DONE 으로 멀쩡히 남아 있는데).
     * 이제 마지막 소유자까지 내려간 시점에 '종료 관측' 이력 한 줄을 남기고, 조회 범위에는 계속 둔다.
     *
     * <p>**사라진 다음 상태는 스케줄 유무가 정한다**(2026-09-22, `UR-260922-1` — 예전의 서비스요청 완료 "추정"을 대체):
     * 스케줄을 잡았던 건({@code IN_PROGRESS})은 {@code DONE}, 안 잡았던 건({@code WAITING})은 {@code UNTRACKED}
     * (추적불가 — 이 보드를 안 쓰고 처리됐을 수 있다. 사람이 확정한다). {@link ItsmRequest#onDroppedFromTodo()}.
     */
    private void deactivateMissingOwners(Long userId, Set<String> seen, LocalDateTime now) {
        for (RequestOwner o : ownerRepo.findByUserIdAndActiveTrue(userId)) {
            if (seen.contains(o.getReqNo())) continue;
            o.setActive(false);
            ownerRepo.save(o);
            if (!ownerRepo.findByReqNoAndActiveTrue(o.getReqNo()).isEmpty()) continue;  // 다른 사람 To-Do 에 아직 있음
            reqRepo.findByReqNo(o.getReqNo()).ifPresent(r -> {
                String fromStatus = r.getWorkStatus();
                r.onDroppedFromTodo();
                // 완료로 내려간 건의 완료 시각 근사치. 추적불가에는 붙이지 않는다 — 완료가 아니라 "모름"이라
                // 완료 목록·주간보고 숫자를 부풀리면 안 된다. 사람이 확정할 때 그 시각이 찍힌다.
                if (BoardStatus.DONE.equals(r.getWorkStatus()) && r.getDoneAt() == null) r.setDoneAt(now);
                RequestStatusHistory h = new RequestStatusHistory();
                h.setReqNo(r.getReqNo());
                h.setFromStatus(fromStatus);  h.setToStatus(r.getWorkStatus());
                h.setFromStaNm(r.getItsmStaNm());    h.setToStaNm(RequestStatusHistory.CLOSED_MARK);
                h.setToAssignee(r.getAssigneeName());
                h.setActorName(r.getAssigneeName());
                h.setObservedAt(now);
                histRepo.save(h);
                r.setLastChangedAt(now);
                reqRepo.save(r);
                log.info("ITSM To-Do 에서 내려감 reqNo={} {} → {} (미러는 유지)", r.getReqNo(), fromStatus, r.getWorkStatus());
            });
        }
    }

    private List<JsonNode> fetchAllPages(String token) {
        List<JsonNode> all = new ArrayList<>();
        int maxPages = Math.max(1, props.getPoll().getMaxPages());
        for (int page = 0; page < maxPages; page++) {
            ItsmClient.RawResult res = client.call(props.getEndpoints().getMyTodos(), token, payload(page));
            if (!res.ok()) {
                if (res.status() == 401 || res.status() == 403)
                    throw new AuthFailedException("ITSM 토큰이 거부되었습니다 (HTTP " + res.status() + ")");
                throw new IllegalStateException("ITSM 목록 조회 실패 (HTTP " + res.status() + ")");
            }
            List<JsonNode> items = mapper.extractList(res.body());
            all.addAll(items);
            if (items.isEmpty() || mapper.isLastPage(res.body())) break;
        }
        return all;
    }

    private Map<String, Object> payload(int page) {
        Map<String, Object> p = new HashMap<>();
        p.put("direction", "DESC");
        p.put("pageNum", page);
        p.put("itemPerPage", props.getPoll().getPageSize());
        p.put("usePaging", "Y");
        p.put("sortBy", "reqDt");
        p.put("searchCriteria", Map.of());
        return p;
    }

    /** 자격증명 문제 — 재시도 금지 신호 */
    public static class AuthFailedException extends RuntimeException {
        public AuthFailedException(String m) { super(m); }
    }
}
