package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppTime;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestSchedule;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestScheduleRepository;
import com.pulmuone.sdeboard.repo.RequestStatusHistoryRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.ScheduleDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 요청 처리 일정 — 등록·수정·취소·캘린더 (`UR-260922-1`).
 *
 * <p><b>이 클래스가 지키는 것들</b>
 * <ul>
 *   <li>일정은 <b>담당자 표시를 바꾸지 않는다</b>(사용자 결정 §8-5). 여기서 바뀌는 건 요청 상태(대기↔작업중)뿐이다.</li>
 *   <li>요청당 유효한(ACTIVE) 일정은 <b>하나</b>. 수정은 옛 행을 {@code REVISED} 로 닫고 새 행을 연다 — 이력이 자연히 쌓인다.</li>
 *   <li>수정·취소는 <b>사유 필수</b>. 승인 절차는 없다 — SME·리더가 나중에 읽는 기록이다.</li>
 *   <li>종료된 요청(작업완료·추적불가)에는 일정을 잡지 못한다 — 추적불가는 먼저 사람이 완료로 확정한다.</li>
 * </ul>
 *
 * <p>작업자 지정(§8-7): SDE 는 본인만, SME·리더는 조회 범위 안의 SDE(·리더)를 지정해 <b>할당</b>할 수 있다.
 * 이 값은 인력풀 차수({@code sde_assignment})와 무관하다(§8-6).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleService {

    /** 사유 길이 상한 — 컬럼(500)이 대신 잘라 내지 않게 서비스가 먼저 막는다. */
    static final int MAX_REASON = 500;
    /** 한 일정의 최대 길이. 오타(연도 하나 틀림)로 캘린더 전체가 한 줄로 덮이는 것을 막는 안전장치다. */
    static final Duration MAX_SPAN = Duration.ofDays(60);
    /** 캘린더 한 번에 읽는 최대 기간(월 뷰 6주 + 여유). */
    static final Duration MAX_RANGE = Duration.ofDays(93);

    private static final Set<String> ROLES = Set.of("SME", "SDE_LEADER", "SDE");
    private static final Set<String> MANAGER_ROLES = Set.of("SME", "SDE_LEADER");
    private static final Set<String> WORKER_ROLES = Set.of("SDE", "SDE_LEADER");

    private final RequestScheduleRepository scheduleRepo;
    private final ItsmRequestRepository reqRepo;
    private final RequestStatusHistoryRepository histRepo;
    private final AppUserRepository userRepo;
    private final DashboardService dashboard;

    // ── 등록 ─────────────────────────────────────────────────────────────

    public ScheduleView create(UserSession s, ScheduleCreateRequest in) {
        AppUser me = requireUser(s);
        if (in == null || in.reqNo() == null || in.reqNo().isBlank())
            throw bad("요청번호가 필요합니다.");
        validateRange(in.start(), in.end());

        ItsmRequest r = dashboard.requireInScopeRequest(s, in.reqNo());
        String st = r.getWorkStatus();
        if (BoardStatus.IN_PROGRESS.equals(st) && scheduleRepo.findFirstByReqNoAndStatus(r.getReqNo(), RequestSchedule.ACTIVE).isPresent())
            throw conflict("이미 등록된 일정이 있습니다. 일정을 수정하거나 취소하세요.");
        if (BoardStatus.DONE.equals(st))
            throw conflict("작업완료된 요청에는 일정을 등록할 수 없습니다.");
        if (BoardStatus.UNTRACKED.equals(st))
            throw conflict("추적불가 상태입니다. 처리가 끝난 건이면 먼저 '작업완료로 확정'하세요.");

        AppUser assignee = resolveAssignee(s, me, in.assigneeId());
        LocalDateTime now = AppTime.now();
        RequestSchedule x = new RequestSchedule();
        x.setReqNo(r.getReqNo());
        x.setAssigneeId(assignee.getId());
        x.setCreatedBy(me.getId());
        x.setStartDt(in.start());
        x.setEndDt(in.end());
        x.setStatus(RequestSchedule.ACTIVE);
        x.setCreatedAt(now);
        scheduleRepo.save(x);

        transition(r, BoardStatus.IN_PROGRESS, "스케줄 등록", me, now);
        return view(x, r);
    }

    // ── 수정 ─────────────────────────────────────────────────────────────

    public ScheduleView update(UserSession s, Long id, ScheduleUpdateRequest in) {
        AppUser me = requireUser(s);
        RequestSchedule old = requireActive(id);
        ItsmRequest r = requireEditableRequest(s, old);
        if (in == null) throw bad("수정할 내용이 없습니다.");
        String reason = requireReason(in.reason());
        validateRange(in.start(), in.end());

        Long newAssignee = in.assigneeId() == null ? old.getAssigneeId() : resolveAssignee(s, me, in.assigneeId()).getId();
        if (in.start().equals(old.getStartDt()) && in.end().equals(old.getEndDt()) && newAssignee.equals(old.getAssigneeId()))
            throw bad("바뀐 내용이 없습니다.");

        LocalDateTime now = AppTime.now();
        old.setStatus(RequestSchedule.REVISED);
        old.setReason(reason);
        old.setClosedBy(me.getId());
        old.setClosedAt(now);
        scheduleRepo.save(old);

        RequestSchedule x = new RequestSchedule();
        x.setReqNo(old.getReqNo());
        x.setAssigneeId(newAssignee);
        x.setCreatedBy(me.getId());
        x.setStartDt(in.start());
        x.setEndDt(in.end());
        x.setStatus(RequestSchedule.ACTIVE);
        x.setPrevId(old.getId());
        x.setCreatedAt(now);
        scheduleRepo.save(x);

        r.setLastChangedAt(now);
        reqRepo.save(r);
        return view(x, r);
    }

    // ── 취소 ─────────────────────────────────────────────────────────────

    public void cancel(UserSession s, Long id, ScheduleCancelRequest in) {
        AppUser me = requireUser(s);
        RequestSchedule x = requireActive(id);
        ItsmRequest r = requireEditableRequest(s, x);
        String reason = requireReason(in == null ? null : in.reason());

        LocalDateTime now = AppTime.now();
        x.setStatus(RequestSchedule.CANCELLED);
        x.setReason(reason);
        x.setClosedBy(me.getId());
        x.setClosedAt(now);
        scheduleRepo.save(x);

        // 다른 사람에게 넘기려는 취소 — 요청은 다시 대기로 돌아가 아무 일정도 없는 상태가 된다
        transition(r, BoardStatus.WAITING, "스케줄 취소", me, now);
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    /** 기간과 겹치는 **유효한** 일정 — 내 조회 범위의 건 + 내가 작업자이거나 등록한 건. */
    @Transactional(readOnly = true)
    public CalendarResponse calendar(UserSession s, LocalDateTime from, LocalDateTime to) {
        requireUser(s);
        if (from == null || to == null || !to.isAfter(from)) throw bad("조회 기간이 올바르지 않습니다.");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw bad("한 번에 조회할 수 있는 기간은 최대 93일입니다.");

        Set<String> visible = dashboard.visibleReqNos(s);
        List<RequestSchedule> rows = scheduleRepo
                .findByStatusAndStartDtLessThanAndEndDtGreaterThan(RequestSchedule.ACTIVE, to, from).stream()
                .filter(x -> visible.contains(x.getReqNo())
                        || s.getUserId().equals(x.getAssigneeId()) || s.getUserId().equals(x.getCreatedBy()))
                .sorted(Comparator.comparing(RequestSchedule::getStartDt).thenComparing(RequestSchedule::getId))
                .toList();
        return new CalendarResponse(views(rows), from, to);
    }

    /** 등록 화면의 작업자 후보. SDE 는 본인뿐이다. */
    @Transactional(readOnly = true)
    public List<AssigneeOption> assignees(UserSession s) {
        AppUser me = requireUser(s);
        List<AppUser> pool = candidates(s, me);
        return pool.stream()
                .sorted(Comparator.comparing((AppUser u) -> !u.getId().equals(me.getId()))
                        .thenComparing(u -> u.getName() == null ? "" : u.getName()))
                .map(u -> new AssigneeOption(u.getId(), u.getName(), u.getRole(), u.getTeam(), u.getId().equals(me.getId())))
                .toList();
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /** 작업자로 지정할 수 있는 사람 = 본인 + (SME·리더라면) 조회 범위 안의 SDE·리더. */
    private List<AppUser> candidates(UserSession s, AppUser me) {
        Map<Long, AppUser> out = new LinkedHashMap<>();
        out.put(me.getId(), me);
        if (MANAGER_ROLES.contains(me.getRole()))
            for (AppUser u : dashboard.scopeMembersOf(s))
                if (WORKER_ROLES.contains(u.getRole())) out.putIfAbsent(u.getId(), u);
        return List.copyOf(out.values());
    }

    private AppUser resolveAssignee(UserSession s, AppUser me, Long assigneeId) {
        if (assigneeId == null || assigneeId.equals(me.getId())) return me;
        if (!MANAGER_ROLES.contains(me.getRole()))
            throw forbidden("SDE 는 본인 일정만 등록할 수 있습니다.");
        return candidates(s, me).stream().filter(u -> u.getId().equals(assigneeId)).findFirst()
                .orElseThrow(() -> bad("작업자로 지정할 수 없는 사람입니다(조회 범위 밖이거나 SDE 가 아닙니다)."));
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) throw bad("시작·종료 일시를 모두 입력하세요.");
        if (!end.isAfter(start)) throw bad("종료 일시는 시작 일시보다 뒤여야 합니다.");
        if (Duration.between(start, end).compareTo(MAX_SPAN) > 0) throw bad("한 일정은 최대 60일까지 잡을 수 있습니다.");
    }

    private String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw bad("사유를 입력하세요.");
        if (r.length() > MAX_REASON) throw bad("사유는 " + MAX_REASON + "자 이내로 입력하세요.");
        return r;
    }

    private RequestSchedule requireActive(Long id) {
        RequestSchedule x = scheduleRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."));
        if (!RequestSchedule.ACTIVE.equals(x.getStatus())) throw conflict("이미 수정되었거나 취소된 일정입니다. 화면을 새로 고치세요.");
        return x;
    }

    /** 이 일정을 고치거나 취소할 수 있는가 — 등록자·작업자 본인, 또는 그 요청이 조회 범위에 든 SME·리더. */
    private ItsmRequest requireEditableRequest(UserSession s, RequestSchedule x) {
        ItsmRequest r = reqRepo.findByReqNo(x.getReqNo()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요청을 찾을 수 없습니다."));
        boolean mine = s.getUserId().equals(x.getCreatedBy()) || s.getUserId().equals(x.getAssigneeId());
        boolean manager = MANAGER_ROLES.contains(s.getRole()) && dashboard.visibleReqNos(s).contains(x.getReqNo());
        if (!mine && !manager) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다.");
        if (BoardStatus.isClosed(r.getWorkStatus())) throw conflict("이미 종료된 요청의 일정은 바꿀 수 없습니다.");
        return r;
    }

    /** 상태 전이 한 번 — 요청과 이력을 함께 갱신한다. */
    private void transition(ItsmRequest r, String to, String mark, AppUser actor, LocalDateTime now) {
        String from = r.getWorkStatus();
        if (Objects.equals(from, to)) return;
        r.setWorkStatus(to);
        r.setLastChangedAt(now);
        reqRepo.save(r);
        RequestStatusHistory h = new RequestStatusHistory();
        h.setReqNo(r.getReqNo());
        h.setFromStatus(from);        h.setToStatus(to);
        h.setFromStaNm(r.getItsmStaNm()); h.setToStaNm(mark);
        h.setToAssignee(r.getAssigneeName());
        h.setActorPerId(actor.getItsmPerId());
        h.setActorName(actor.getName());
        h.setObservedAt(now);
        histRepo.save(h);
    }

    private ScheduleView view(RequestSchedule x, ItsmRequest r) {
        return DashboardService.toScheduleView(x, r, names(List.of(x)));
    }

    private List<ScheduleView> views(List<RequestSchedule> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, String> names = names(rows);
        Map<String, ItsmRequest> reqs = reqRepo.findByReqNoIn(rows.stream().map(RequestSchedule::getReqNo).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ItsmRequest::getReqNo, r -> r, (a, b) -> a));
        return rows.stream().map(x -> DashboardService.toScheduleView(x, reqs.get(x.getReqNo()), names)).toList();
    }

    private Map<Long, String> names(List<RequestSchedule> rows) {
        Set<Long> ids = new HashSet<>();
        for (RequestSchedule x : rows) {
            ids.add(x.getAssigneeId()); ids.add(x.getCreatedBy());
            if (x.getClosedBy() != null) ids.add(x.getClosedBy());
        }
        return userRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u.getName() == null ? u.getLoginId() : u.getName(), (a, b) -> a));
    }

    private AppUser requireUser(UserSession s) {
        AppUser me = userRepo.findById(s.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        if (!ROLES.contains(me.getRole())) throw forbidden("일정을 다룰 수 없는 역할입니다.");
        return me;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    private static ResponseStatusException forbidden(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
}
