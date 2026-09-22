package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.RequestDerivation;
import com.pulmuone.sdeboard.domain.RequestFile;
import com.pulmuone.sdeboard.domain.RequestSchedule;
import com.pulmuone.sdeboard.web.dto.FileDtos.FileView;
import com.pulmuone.sdeboard.web.dto.ScheduleDtos.ScheduleView;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;
import com.pulmuone.sdeboard.web.dto.RequestDtos.*;
import com.pulmuone.sdeboard.web.dto.StatsDtos.*;

import com.pulmuone.sdeboard.domain.AppTime;

import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.AttachmentRef;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestOwner;
import com.pulmuone.sdeboard.domain.CommentRead;
import com.pulmuone.sdeboard.domain.NoteRead;
import com.pulmuone.sdeboard.domain.RequestComment;
import com.pulmuone.sdeboard.domain.RequestNote;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.domain.SdeAssignment;
import com.pulmuone.sdeboard.repo.*;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final ItsmRequestRepository reqRepo;
    private final RequestStatusHistoryRepository histRepo;
    private final AttachmentRefRepository attRepo;
    private final AppUserRepository userRepo;
    private final RequestOwnerRepository ownerRepo;
    private final SdeAssignmentRepository poolRepo;
    // 서비스가 아니라 리포지토리를 직접 쓴다 — RequestNoteService 가 이 클래스를 쓰므로 순환이 된다
    private final RequestNoteRepository noteRepo;
    private final NoteReadRepository noteReadRepo;
    // ⚠️ 코멘트도 서비스가 아니라 리포지토리를 직접 쓴다 — RequestCommentService 가 이 클래스를 쓰므로 순환이 된다
    private final RequestCommentRepository commentRepo;
    private final CommentReadRepository commentReadRepo;
    private final SessionRegistry sessions;
    private final ItsmProperties props;
    private final RequestScheduleRepository scheduleRepo;
    private final RequestFileRepository fileRepo;

    /** '신규 유입'으로 묶는 기준 시간. yml `app.new-request-hours` (기본 24). */
    @org.springframework.beans.factory.annotation.Value("${app.new-request-hours:24}")
    private int newRequestHours;

    private static final List<String> DIST_ORDER = BoardStatus.ORDER;

    /**
     * 담당자별 현황의 상태 열 — {@link #DIST_ORDER} 에서 <b>완료만</b> 뺀 것이다.
     * 표가 답하는 질문이 "지금 누가 무엇을 들고 있나" 라 끝난 일은 자리를 차지할 이유가 없고,
     * 완료 건수는 KPI 카드에 있다. <b>열 목록·순서의 유일한 출처</b>다.
     */
    private static final List<String> ROW_ORDER = DIST_ORDER.stream().filter(s -> !BoardStatus.DONE.equals(s)).toList();

    /**
     * 아직 아무도 안 들고 있는 건이 서는 행.
     * <p>이 행이 없으면 <b>표 합계가 상태 분포보다 조용히 모자란다</b> — 미할당 건이 어느 담당자 행에도
     * 안 들어가기 때문이다. 숫자가 안 맞는 표는 틀린 표보다 나쁘다(어디가 틀렸는지 알 수 없다).
     */
    static final String UNASSIGNED = "미배정";

    /**
     * 조회 범위에 기여하는 사람들 — **이 사용자의 화면을 채워 줄 수 있는 가입자**.
     * 화면의 "가입 N명 중 M명 연동" 표기가 이 목록을 센다.
     *
     * <p>SME 는 같은 법인 SME + <b>그 법인에 배정된 SDE</b>(인력풀)까지 센다.
     * 2026-09-08 이후 SME 가 보는 건의 상당수는 SDE 가 미러링해 주는 것이므로,
     * SME 만 세면 커버리지를 실제보다 낮게 말하게 된다.
     */
    private List<AppUser> scopeMembers(UserSession s) {
        AppUser me = userRepo.findById(s.getUserId()).orElseThrow();
        List<AppUser> members;
        if ("SME".equals(me.getRole()) && me.getCorpCd() != null) {
            members = new ArrayList<>(userRepo.findByRoleAndCorpCd("SME", me.getCorpCd()));
            members.addAll(corpSdes(me.getCorpNm()));           // 우리 법인 담당 SDE (인력풀 배정 기준)
        } else if ("SDE_LEADER".equals(me.getRole()) && me.getTeam() != null) {
            // 리더 범위에 **팀원 SDE 를 포함**한다(2026-09-08).
            // SDE 가 아무도 가입하지 않았으면 예전과 똑같이 리더들 것만 잡힌다.
            members = userRepo.findByTeam(me.getTeam());
        } else {
            // SDE 본인 — 자기에게 할당된 것만 본다(아이디어 원문 그대로)
            members = List.of(me);
        }
        if (members.stream().noneMatch(u -> u.getId().equals(me.getId()))) {
            members = new ArrayList<>(members);
            members.add(me);
        }
        return members.stream().distinct().toList();
    }

    /** 인력풀에서 이 법인에 배정된 SDE 들. 커버리지 표기에만 쓴다(범위 판정은 요청의 법인으로 한다). */
    private List<AppUser> corpSdes(String corpNm) {
        if (corpNm == null || corpNm.isBlank()) return List.of();
        List<Long> ids = poolRepo.findByCorpNm(corpNm).stream().map(SdeAssignment::getUserId).distinct().toList();
        return ids.isEmpty() ? List.of() : userRepo.findAllById(ids);
    }

    /**
     * 이 사용자에게 보여 줄 요청번호 집합.
     *
     * <p><b>SME 는 "요청의 법인" 기준</b>이다(2026-09-08, §F) — `req_comp_nm` 이 내 법인이면
     * <b>누가 미러링했든</b> 보인다. 예전처럼 소유자 기준이면, 도입 전에 이미 SDE 에게 넘어가
     * 지금도 그 사람 To-Do 에 살아 있는 건이 SME 화면에 끝내 나타나지 않는다.
     *
     * <p>인력풀 배정(1~5차)을 기준으로 삼지 않는 이유: <b>SDE 는 여러 법인을 담당</b>하므로
     * 그 사람의 To-Do 를 통째로 넣으면 <b>다른 법인 요청이 섞여 들어온다.</b>
     *
     * <p>소유자 기준 집합도 <b>합집합으로 함께</b> 남긴다 — 법인명 표기가 어긋나도 예전에 보이던 것이 사라지지 않게.
     */
    private Set<String> scopeReqNos(UserSession s) {
        AppUser me = userRepo.findById(s.getUserId()).orElseThrow();
        Set<String> reqNos = new HashSet<>();

        List<Long> ids = scopeMembers(s).stream().map(AppUser::getId).toList();
        reqNos.addAll(ownedReqNos(ids));                                        // 소유자 기준(종전, 이관 제외 — 아래)

        if ("SME".equals(me.getRole()) && notBlank(me.getCorpNm())) {           // 법인 기준(신규)
            String mine = normCorp(me.getCorpNm());
            reqRepo.findAll().stream()
                    .filter(r -> mine.equals(normCorp(r.getReqCompNm())))
                    .forEach(r -> reqNos.add(r.getReqNo()));
        }
        return reqNos;
    }

    /**
     * {@code ids} 가 (과거 포함) 소유했던 요청번호 — 단 **이관된 건은 뺀다**(`UR-260916-1`).
     *
     * <p>{@code active=false} 행을 무조건 살려 두면 "완료돼서 내려간 것"과 "남에게 넘어가서
     * 내려간 것"을 구분하지 못한다. 전자는 완료 이력으로 계속 남아야 하지만, 후자는 SDE 본인
     * 화면에 남을 이유가 없다(요청자 표현: "SDE 끼리는 목록 공유 필요가 없다").
     *
     * <p>판정: {@code ids} 안에서 그 요청을 <b>아무도 active 로 안 들고 있는데</b>, {@code ids} <b>밖의
     * 누군가는 여전히 active</b> 면 — 이관된 것이다. {@code ids} 밖에도 아무도 active 가 없으면(전원
     * 종료) 완료 이력으로 남긴다. {@code ids} 안에 한 명이라도 active 면 애초에 뺄 이유가 없다.
     *
     * <p>이 구분은 {@link #todoStates} 의 {@code inTodo}(전체 소유자 기준, 2026-09-08 결정)와는
     * 다른 질문에 답한다 — 거기는 "아직 누군가의 할 일에 있나", 여기는 "그게 <b>이 범위의</b> 일인가".
     */
    private Set<String> ownedReqNos(List<Long> ids) {
        List<RequestOwner> mine = ownerRepo.findByUserIdIn(ids);
        if (mine.isEmpty()) return Set.of();

        Map<String, Boolean> activeWithinScope = new HashMap<>();
        for (RequestOwner o : mine) activeWithinScope.merge(o.getReqNo(), o.isActive(), Boolean::logicalOr);

        List<String> needCheck = activeWithinScope.entrySet().stream()
                .filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        Set<Long> idSet = Set.copyOf(ids);
        Set<String> transferredAway = needCheck.isEmpty() ? Set.of()
                : ownerRepo.findByReqNoIn(needCheck).stream()
                        .filter(o -> o.isActive() && !idSet.contains(o.getUserId()))
                        .map(RequestOwner::getReqNo)
                        .collect(Collectors.toSet());

        return activeWithinScope.keySet().stream()
                .filter(reqNo -> !transferredAway.contains(reqNo))
                .collect(Collectors.toSet());
    }

    /**
     * 요청번호 → To-Do 존재 여부.
     *
     * <p>⚠️ active=true 인 것만 보면 **완료 처리된 건이 화면에서 통째로 사라진다.**
     * ITSM 에서 '변경완료 확인'을 누르면 To-Do 목록에서 빠지기 때문이다(미러 DB엔 DONE 으로 남아 있다).
     * 그래서 범위는 active 무관하게 잡고, 대신 각 건에 '아직 할 일에 있나 / 언제 내려갔나' 를 붙인다.
     *
     * <p><b>2026-09-08 — 판정 기준을 "범위 멤버"에서 "그 요청의 전체 소유자"로 바꿨다.</b>
     * 예전에는 SME 범위에 SDE 가 없어서, <b>SDE 가 한창 처리 중인 건도 SME 화면에서는
     * 'ITSM 할 일 종료'로 보였다</b>(정렬 하단 · KPI closed 집계까지). 보드가 살아 있는 걸 알면서 틀리게 말하던 셈이다.
     *
     * <p><b>⚠️ 2026-09-14 — `firstSeen` 만은 전체 소유자가 아니라 "나(세션 사용자)" 기준으로 좁혔다</b>(`UR-260911-2`).
     * `inTodo`/`lastSeen` 은 위 결정대로 전체 소유자 기준을 그대로 둔다 — 이건 '아직 누군가의 할 일에 있나' 를 묻는 것이라
     * 전체 기준이 맞다. 하지만 `firstSeen` 은 "내가 아직 못 본 건인가"({@code DashboardPage.jsx} 화면 의도)를 답해야 하는데,
     * 전체 소유자 중 최솟값을 쓰면 <b>리더가 접수 단계에서 먼저 훑어본 뒤 시차를 두고 나에게 배정된 건이 나에겐 방금
     * 왔어도 이미 오래된 건으로 보였다</b>(리더가 이틀 전 먼저 본 건 2개가 SDE 화면에서 곧장 '진행 중'으로 빠짐, 실측 확인).
     * 그래서 `firstSeen` 은 <b>세션 사용자 자신의 소유 행</b>에서만 읽는다 — 그 행이 없으면(예: SME 의 법인 기준으로만
     * 잡힌 건) `firstSeen` 은 null 이고 신규로 뜨지 않는다(전과 동일한 결과).
     */
    private Map<String, TodoState> todoStates(Set<String> reqNos, Long meUserId) {
        Map<String, TodoState> m = new HashMap<>();
        if (reqNos.isEmpty()) return m;
        for (RequestOwner o : ownerRepo.findByReqNoIn(List.copyOf(reqNos))) {
            // 이 맵의 keySet 이 곧 조회 범위다 — 넘겨받은 집합 밖은 절대 더하지 않는다.
            // (조회 범위가 리포지토리 질의의 정확성에 암묵적으로 기대게 두지 않는다)
            if (!reqNos.contains(o.getReqNo())) continue;
            TodoState prev = m.get(o.getReqNo());
            boolean inTodo = o.isActive() || (prev != null && prev.inTodo());
            LocalDateTime seen = prev == null || prev.lastSeen() == null || (o.getLastSeen() != null && o.getLastSeen().isAfter(prev.lastSeen()))
                    ? o.getLastSeen() : prev.lastSeen();
            // firstSeen 은 **내(세션 사용자) 소유 행**만 본다 — (userId, reqNo) 는 유일하므로 한 reqNo 당 최대 한 번만 채워진다
            LocalDateTime first = prev == null ? null : prev.firstSeen();
            if (meUserId != null && meUserId.equals(o.getUserId())) first = o.getFirstSeen();
            m.put(o.getReqNo(), new TodoState(inTodo, seen, first));
        }
        // 법인 기준으로만 잡힌 건은 소유 행이 있을 수 있으나, 혹시 없더라도 '추적 중'으로 둔다
        reqNos.forEach(n -> m.putIfAbsent(n, UNKNOWN_TODO));
        return m;
    }

    private Map<String, TodoState> todoStates(UserSession s) {
        return todoStates(scopeReqNos(s), s.getUserId());
    }

    /**
     * inTodo=false 면 lastSeen 이 '아무의 ITSM 할 일에도 남지 않게 된 시각'이다.
     * firstSeen 은 **세션 사용자 본인** 소유 행의 first_seen(2026-09-14 부터 — `UR-260911-2`), 없으면 null.
     */
    private record TodoState(boolean inTodo, LocalDateTime lastSeen, LocalDateTime firstSeen) {}

    private static final TodoState UNKNOWN_TODO = new TodoState(true, null, null);

    private List<ItsmRequest> scopedRequests(UserSession s, Set<String> reqNos) {
        if (reqNos.isEmpty()) return List.of();
        return reqRepo.findAll().stream().filter(r -> reqNos.contains(r.getReqNo())).toList();
    }

    /**
     * 요청 → **지금 그 건을 들고 있는 SDE 의 담당 차수**.
     *
     * <p>담당자 이름(`trfPerId`)이 아니라 <b>소유 관계</b>로 연결한다 — 이름 문자열 매칭은 동명이인·표기 차이로 깨진다.
     * 차수는 <b>그 요청의 법인</b> 기준으로 찾는다(SDE 는 법인마다 다른 차수를 맡는다).
     * <b>2026-09-10 부터 시스템 배정에서 뽑는다</b> — 법인담당SDE 에는 차수가 없기 때문이다.
     * 한 법인에서 차수를 여러 개 맡고 있으면 <b>고르지 않고 이어 붙인다</b>("1·3차").
     * 인력풀에 배정이 없거나 그 담당자가 아직 가입하지 않았으면 null — 화면은 배지를 그냥 생략한다.
     *
     * <p>⚠️ <b>역할 조건은 인력풀의 배정 대상과 같아야 한다.</b> 2026-09-09 부터 리더도 배정될 수 있는데
     * 여기만 {@code SDE} 로 남으면 <b>배정은 됐는데 목록에 차수 배지가 안 뜬다</b> — 어디가 틀렸는지
     * 화면만 봐서는 알 수 없는 종류의 어긋남이다. 그래서 {@code SdePoolService.POOL_ROLES} 와 같은 목록을 쓴다.
     */
    /** 차수 배지가 붙는 역할 — **인력풀 배정 대상과 같아야 한다**(`SdePoolService.POOL_ROLES`). */
    private static final Set<String> TIER_ROLES = Set.of("SDE", "SDE_LEADER");

    private Map<String, String> assigneeTiers(Collection<ItsmRequest> reqs) {
        if (reqs.isEmpty()) return Map.of();

        // ⚠️ **시스템 배정에서만 뽑는다**(2026-09-10). 법인담당SDE 에는 차수가 없다(사용자) —
        //    차수는 시스템 줄에만 있으므로 배지도 거기서 나올 수밖에 없다.
        // ⚠️ 한 사람이 같은 법인에서 **차수를 여러 개** 가질 수 있다(FNC 인사 1차 · 하루 3차).
        //    ITSM 목록이 요청의 시스템을 주지 않아 어느 쪽인지 고를 수 없다. 하나를 고르면
        //    **틀린 숫자를 조용히** 보여주게 되므로, 고르지 않고 있는 그대로 이어 붙인다("1·3차").
        Map<String, SortedSet<Integer>> byCorpUser = new HashMap<>();
        for (SdeAssignment a : poolRepo.findAll()) {
            if (a.getSystemNm() == null || a.getTier() == null) continue;
            byCorpUser.computeIfAbsent(normCorp(a.getCorpNm()) + "|" + a.getUserId(), k -> new TreeSet<>())
                    .add(a.getTier());
        }
        if (byCorpUser.isEmpty()) return Map.of();

        Map<String, String> corpOf = new HashMap<>();
        reqs.forEach(r -> corpOf.put(r.getReqNo(), normCorp(r.getReqCompNm())));
        Map<Long, String> roleOf = userRepo.findAll().stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u.getRole() == null ? "" : u.getRole()));

        Map<String, String> out = new HashMap<>();
        for (RequestOwner o : ownerRepo.findByReqNoIn(List.copyOf(corpOf.keySet()))) {
            if (!o.isActive() || !TIER_ROLES.contains(roleOf.get(o.getUserId()))) continue;
            SortedSet<Integer> t = byCorpUser.get(corpOf.get(o.getReqNo()) + "|" + o.getUserId());
            if (t != null && !t.isEmpty())
                out.putIfAbsent(o.getReqNo(), t.stream().map(String::valueOf).collect(Collectors.joining("·")));
        }
        return out;
    }

    /**
     * 법인명 비교 정규화 — **공백 무시**.
     * ITSM 이 법인코드를 주지 않아 문자열로 맞출 수밖에 없고, 가입 때 손으로 적은 값과 비교하므로
     * 표기 차이 하나로 **조용히 0건**이 되는 것을 막아야 한다. (`ItsmFlow` 의 상태명 비교와 같은 방식)
     */
    static String normCorp(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    public MeResponse me(UserSession s) {
        AppUser me = userRepo.findById(s.getUserId()).orElseThrow();
        List<AppUser> members = scopeMembers(s);
        Set<Long> loggedIn = sessions.active().stream().map(UserSession::getUserId).collect(Collectors.toSet());
        int linked = (int) members.stream()
                .filter(u -> "LINKED".equals(u.getLinkStatus()) && (u.getLastSyncAt() != null || loggedIn.contains(u.getId())))
                .count();
        String scopeLabel = switch (me.getRole() == null ? "" : me.getRole()) {
            case "SME" -> me.getCorpNm() == null ? "내 요청" : me.getCorpNm() + " 법인";
            case "SDE" -> "내 작업";     // SDE 는 자기에게 할당된 것만 본다
            default -> me.getTeam() == null ? "내 팀" : me.getTeam();
        };
        return new MeResponse(me.getLoginId(), me.getName(), me.getRole(), me.getLeaderRank(),
                me.getCorpNm(), me.getTeam(),
                scopeLabel, members.size(), linked,
                s.getSyncState(), s.getSyncMessage(), s.getLastSyncAt(),
                props.getSession().isKeepCredential());
    }

    public List<RequestView> list(UserSession session, String filter) {
        return list(session, filter, null, null);
    }

    /**
     * 목록 조회. `from`/`to` 는 **완료일(done_at) 기준** 경계이며 완료 목록에서만 의미가 있다
     * (다른 필터에 붙여도 완료된 건만 남는 결과가 되므로, 화면은 COMPLETED 와 함께만 보낸다).
     */
    public List<RequestView> list(UserSession session, String filter, LocalDate from, LocalDate to) {
        LocalDate today = AppTime.today();
        Map<String, Integer> attCounts = attachmentCounts();
        Map<String, TodoState> todo = todoStates(session);
        Map<String, String> tiers = assigneeTiers(scopedRequests(session, todo.keySet()));
        Map<String, NoteBadge> noted = noteBadges(todo.keySet(), session.getUserId());
        Map<String, Integer> unread = commentBadges(todo.keySet(), session.getUserId());
        Map<String, ScheduleInfo> sched = scheduleInfos(todo.keySet());
        Map<String, Integer> files = fileCounts(todo.keySet());
        return scopedRequests(session, todo.keySet()).stream()
                .filter(r -> matchFilter(r, filter, today, todo, unread))
                .filter(r -> inDonePeriod(r, from, to))
                // 끝난 건(완료·할 일에서 내려감)은 아래로, 지연 건은 위로, 그 다음 기한 순
                .sorted(Comparator
                        .comparing((ItsmRequest r) -> closedOrDone(r, todo) ? 1 : 0)
                        .thenComparing(r -> RequestDerivation.late(r, today) ? 0 : 1)
                        .thenComparing(r -> RequestDerivation.dueDate(r) == null ? LocalDate.MAX : RequestDerivation.dueDate(r)))
                .map(r -> toDto(r, today, attCounts.getOrDefault(r.getReqNo(), 0),
                        todo.getOrDefault(r.getReqNo(), UNKNOWN_TODO), tiers.get(r.getReqNo()),
                        noted.getOrDefault(r.getReqNo(), NoteBadge.NONE),
                        unread.getOrDefault(r.getReqNo(), 0),
                        sched.get(r.getReqNo()), files.getOrDefault(r.getReqNo(), 0)))
                .collect(Collectors.toList());
    }

    /**
     * 끝난 건인가 — 작업완료·추적불가. 상태 하나로 판정한다(2026-09-22, 예전에는 상태 + To-Do 잔존을 같이 봤다).
     * 이제 To-Do 에서 사라지는 순간 상태가 바뀌므로({@code SyncService}) 두 번 물을 이유가 없다.
     */
    private boolean closedOrDone(ItsmRequest r, Map<String, TodoState> todo) {
        return BoardStatus.isClosed(r.getWorkStatus());
    }

    /**
     * 상태 셀렉트의 한 항목.
     * <p>⚠️ {@code unread} 는 <b>보는 사람마다 다른 값</b>이라 필터에 들어온다 —
     * 같은 `새 댓글` 조회가 사람마다 다른 목록을 낸다. 그게 이 항목의 목적이다.
     */
    private boolean matchFilter(ItsmRequest r, String f, LocalDate today, Map<String, TodoState> todo,
                                Map<String, Integer> unread) {
        if (f == null || f.equals("ALL")) return true;
        return switch (f) {
            case "LATE" -> RequestDerivation.late(r, today);
            case "INTAKE" -> "INTAKE".equals(r.getAssignStage());
            case "LEADERP" -> "LEADER".equals(r.getAssignStage());
            case "OPEN" -> !closedOrDone(r, todo);
            case "CLOSED" -> !todo.getOrDefault(r.getReqNo(), UNKNOWN_TODO).inTodo();
            // 완료 목록 — done_at 이 채워진 건만. done_at 은 완료(DONE)로 관측된 건에만 붙으므로
            // 반려·취소로 To-Do 에서 내려간 건은 여기 섞이지 않는다.
            case "COMPLETED" -> r.getDoneAt() != null;
            // 남기기만 하고 아무도 못 찾으면 코멘트가 메신저보다 나을 게 없다(사용자 지적 2026-09-11)
            case "CMT" -> unread.getOrDefault(r.getReqNo(), 0) > 0;
            default -> f.equals(r.getWorkStatus());
        };
    }

    private boolean inDonePeriod(ItsmRequest r, LocalDate from, LocalDate to) {
        return inPeriod(r.getDoneAt(), from, to);
    }

    /** 완료일 기간 경계. 경계일은 **양쪽 다 포함**한다(from 00:00 ~ to 23:59:59). (테스트 접근용 package-private) */
    static boolean inPeriod(LocalDateTime d, LocalDate from, LocalDate to) {
        if (from == null && to == null) return true;
        if (d == null) return false;
        if (from != null && d.isBefore(from.atStartOfDay())) return false;
        return to == null || !d.isAfter(to.atTime(LocalTime.MAX));
    }

    /**
     * 작업 완료 목록 — 기간(완료일) 안의 완료 건 + 주간보고에 그대로 붙일 집계 한 줄.
     *
     * ⚠️ 여기서 말하는 완료일은 **우리가 완료를 처음 관측한 시각**이다. ITSM 목록 API 에 완료일 필드가
     * 없어서 만든 값이고, 폴링 주기(5분)와 **그때 세션이 살아 있었는지**에 따라 늦어질 수 있다.
     * 그래서 응답에 `earliestKnown`(우리가 가진 가장 이른 완료 관측)과 `note` 를 함께 실어,
     * 화면이 "이 숫자가 어디까지 믿을 만한지"를 감추지 않게 한다.
     */
    public DoneListResponse doneList(UserSession session, LocalDate from, LocalDate to) {
        // 기간 없이 한 번만 읽고 메모리에서 자른다 — 조회 범위 계산이 두 번 돌지 않게
        List<RequestView> all = list(session, "COMPLETED", null, null);
        List<RequestView> rows = all.stream().filter(r -> inPeriod(r.doneAt(), from, to)).toList();
        List<CountView> byCorp = countBy(rows, r -> blankTo(r.reqCompNm(), r.dept(), "법인 미상"));
        List<CountView> byAssignee = countBy(rows, r -> blankTo(r.assigneeName(), null, "담당자 미상"));

        // 기간과 무관하게, 우리가 완료를 관측하기 시작한 지점 — 그 이전 완료 건은 소급이 불가능하다
        LocalDateTime earliest = all.stream()
                .map(RequestView::doneAt).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);

        String note = "완료일은 ITSM 원본이 아니라 **이 보드가 완료를 처음 관측한 시각**입니다"
                + " (폴링 5분 주기 · 관측은 담당자 세션이 살아 있는 동안에만 일어납니다)."
                + (earliest == null ? " 아직 관측된 완료 건이 없습니다."
                                    : " 관측 시작 이전에 완료된 건은 포함되지 않습니다.");
        return new DoneListResponse(rows, rows.size(), byCorp, byAssignee, from, to, earliest, note);
    }

    private static String blankTo(String a, String b, String fallback) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return fallback;
    }

    /** 건수 많은 순 → 같으면 이름순. 주간보고에 그대로 옮겨 적을 수 있는 순서다. */
    private List<CountView> countBy(List<RequestView> rows, java.util.function.Function<RequestView, String> key) {
        return rows.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new CountView(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CountView::count).reversed()
                        .thenComparing(CountView::label))
                .toList();
    }

    public DetailResponse detail(UserSession session, String reqNo) {
        LocalDate today = AppTime.today();
        Map<String, TodoState> todo = todoStates(session);
        if (!todo.containsKey(reqNo))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "조회 범위에 없는 요청입니다.");
        ItsmRequest r = reqRepo.findByReqNo(reqNo).orElseThrow();
        List<AttachmentRef> refs = attRepo.findByReqNo(reqNo);
        List<HistoryView> hist = histRepo.findByReqNoOrderByObservedAtDesc(reqNo).stream()
                .map(h -> new HistoryView(h.getFromStatus(), h.getToStatus(),
                        h.getFromStaNm(), h.getToStaNm(),
                        h.getFromAssignee(), h.getToAssignee(),
                        h.getActorPerId(), h.getActorName(), h.getObservedAt()))
                .collect(Collectors.toList());
        List<AttachmentView> atts = refs.stream()
                .map(a -> new AttachmentView(a.getId(), a.getFileName(), a.getFileSize(), a.getContentType()))
                .collect(Collectors.toList());
        List<RequestFile> fileRows = fileRepo.findByReqNoOrderByIdAsc(reqNo);
        return new DetailResponse(toDto(r, today, refs.size(), todo.getOrDefault(reqNo, UNKNOWN_TODO),
                        assigneeTiers(List.of(r)).get(reqNo),
                        noteBadges(Set.of(reqNo), session.getUserId()).getOrDefault(reqNo, NoteBadge.NONE),
                        commentBadges(Set.of(reqNo), session.getUserId()).getOrDefault(reqNo, 0),
                        scheduleInfos(Set.of(reqNo)).get(reqNo), fileRows.size()),
                hist, atts, scheduleViews(reqNo, r), fileViews(fileRows, session));
    }

    /** 이 요청의 일정 이력 전부(최신 먼저) — 수정·취소 사유가 여기서 나간다. */
    private List<ScheduleView> scheduleViews(String reqNo, ItsmRequest r) {
        List<RequestSchedule> rows = scheduleRepo.findByReqNoOrderByIdDesc(reqNo);
        Map<Long, String> names = userNames(rows.stream()
                .flatMap(x -> java.util.stream.Stream.of(x.getAssigneeId(), x.getCreatedBy(), x.getClosedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(x -> toScheduleView(x, r, names)).toList();
    }

    static ScheduleView toScheduleView(RequestSchedule x, ItsmRequest r, Map<Long, String> names) {
        return new ScheduleView(x.getId(), x.getReqNo(), r == null ? null : r.getTitle(), r == null ? null : r.getWorkStatus(),
                x.getAssigneeId(), names.get(x.getAssigneeId()),
                x.getCreatedBy(), names.get(x.getCreatedBy()),
                x.getStartDt(), x.getEndDt(), x.getStatus(), x.getReason(),
                x.getClosedBy(), x.getClosedBy() == null ? null : names.get(x.getClosedBy()), x.getClosedAt(), x.getCreatedAt());
    }

    private List<FileView> fileViews(List<RequestFile> rows, UserSession session) {
        Map<Long, String> names = userNames(rows.stream().map(RequestFile::getUploadedBy).collect(Collectors.toSet()));
        return rows.stream().map(f -> new FileView(f.getId(), f.getReqNo(), f.getFileName(), f.getFileSize(), f.getContentType(),
                f.getUploadedBy(), names.get(f.getUploadedBy()), f.getUploadedAt(),
                "SME".equals(session.getRole()))).toList();
    }

    private Map<Long, String> userNames(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return userRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u.getName() == null ? u.getLoginId() : u.getName(), (a, b) -> a));
    }

    /**
     * 목록 한 줄에 붙일 일정 요약 — 요청 하나에 일정 행이 여럿(수정·취소 이력)이어도 **한 번에** 읽는다.
     * 유효한(ACTIVE) 행이 지금의 일정이고, 나머지(REVISED·CANCELLED)의 수가 `changes`, 가장 늦게 끝난 행이 "최근 변경"이다.
     */
    private Map<String, ScheduleInfo> scheduleInfos(Set<String> reqNos) {
        if (reqNos.isEmpty()) return Map.of();
        List<RequestSchedule> all = scheduleRepo.findByReqNoIn(reqNos);
        if (all.isEmpty()) return Map.of();
        Map<Long, String> names = userNames(all.stream().map(RequestSchedule::getAssigneeId).collect(Collectors.toSet()));
        Map<String, List<RequestSchedule>> byReq = all.stream().collect(Collectors.groupingBy(RequestSchedule::getReqNo));
        Map<String, ScheduleInfo> out = new HashMap<>();
        byReq.forEach((reqNo, rows) -> {
            RequestSchedule active = rows.stream().filter(x -> RequestSchedule.ACTIVE.equals(x.getStatus())).findFirst().orElse(null);
            List<RequestSchedule> ended = rows.stream().filter(x -> !RequestSchedule.ACTIVE.equals(x.getStatus())).toList();
            RequestSchedule last = ended.stream()
                    .max(Comparator.comparing((RequestSchedule x) -> x.getClosedAt() == null ? LocalDateTime.MIN : x.getClosedAt())
                            .thenComparing(RequestSchedule::getId)).orElse(null);
            out.put(reqNo, new ScheduleInfo(active == null ? null : active.getId(),
                    active == null ? null : active.getStartDt(), active == null ? null : active.getEndDt(),
                    active == null ? null : active.getAssigneeId(), active == null ? null : names.get(active.getAssigneeId()),
                    ended.size(), last == null ? null : last.getStatus(), last == null ? null : last.getReason(),
                    last == null ? null : last.getClosedAt()));
        });
        return out;
    }

    private Map<String, Integer> fileCounts(Set<String> reqNos) {
        if (reqNos.isEmpty()) return Map.of();
        Map<String, Integer> m = new HashMap<>();
        for (RequestFile f : fileRepo.findByReqNoIn(reqNos)) m.merge(f.getReqNo(), 1, Integer::sum);
        return m;
    }

    /** 조회 범위 안의 요청번호 — 스케줄 서비스가 캘린더·권한 판정에 쓴다. */
    public Set<String> visibleReqNos(UserSession s) { return scopeReqNos(s); }

    /** 이 사용자의 조회 범위에 든 요청을 돌려준다. 범위 밖이면 404 — 존재 여부도 새지 않게 한다. */
    public ItsmRequest requireInScopeRequest(UserSession s, String reqNo) {
        if (!scopeReqNos(s).contains(reqNo))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "조회 범위에 없는 요청입니다.");
        return reqRepo.findByReqNo(reqNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "조회 범위에 없는 요청입니다."));
    }

    /** 조회 범위에 기여하는 사람들(SME=같은 법인 SME+담당 SDE, 리더=팀 전체, SDE=본인). 일정 작업자 후보의 출처다. */
    public List<AppUser> scopeMembersOf(UserSession s) { return scopeMembers(s); }

    public StatsResponse stats(UserSession session) {
        LocalDate today = AppTime.today();
        AppUser me = userRepo.findById(session.getUserId()).orElseThrow();
        Map<String, TodoState> todo = todoStates(session);
        List<ItsmRequest> all = scopedRequests(session, todo.keySet());
        String role = switch (session.getRole() == null ? "" : session.getRole()) {
            case "SME" -> "sme";
            case "SDE" -> "sde";        // 본인 작업 중심 — 배정 대기 같은 관리 지표는 의미가 없다
            default -> "lead";
        };

        int total = all.size();
        int intake = (int) all.stream().filter(r -> "INTAKE".equals(r.getAssignStage())).count();
        int leaderP = (int) all.stream().filter(r -> "LEADER".equals(r.getAssignStage())).count();
        int waiting = (int) all.stream().filter(r -> BoardStatus.WAITING.equals(r.getWorkStatus())).count();
        int inProg = (int) all.stream().filter(r -> BoardStatus.IN_PROGRESS.equals(r.getWorkStatus())).count();
        int untracked = (int) all.stream().filter(r -> BoardStatus.UNTRACKED.equals(r.getWorkStatus())).count();
        int late = (int) all.stream().filter(r -> RequestDerivation.late(r, today)).count();
        int soon = (int) all.stream().filter(r -> RequestDerivation.soon(r, today)).count();
        int done = (int) all.stream().filter(r -> BoardStatus.DONE.equals(r.getWorkStatus())).count();
        int closed = (int) all.stream().filter(r -> !todo.getOrDefault(r.getReqNo(), UNKNOWN_TODO).inTodo()).count();
        int open = (int) all.stream().filter(r -> !closedOrDone(r, todo)).count();
        // 코멘트 **수**가 아니라 **건 수**다 — 셀렉트가 세는 것은 "몇 건을 확인해야 하나" 이기 때문이다
        int newComments = commentBadges(todo.keySet(), session.getUserId()).size();
        KpiView kpis = new KpiView(total, intake, leaderP, waiting, inProg, untracked, late, soon, done, closed, open, newComments);

        List<DistView> dist = DIST_ORDER.stream()
                .map(s -> new DistView(s, all.stream().filter(r -> s.equals(r.getWorkStatus())).count()))
                .collect(Collectors.toList());

        // SME 는 "우리 법인 건을 맡고 있는 사람" 만 본다 — 아래 team(...) 주석 참고
        // 리더 화면에만 **0건인 팀원**을 세운다 — 아래 team(...) 주석 참고.
        String zeroRowTeam = "lead".equals(role) ? me.getTeam() : null;
        return new StatsResponse(role, kpis, dist, team(all, today, zeroRowTeam));
    }

    /**
     * 담당자별 현황 — <b>행 = 이 범위에서 실제로 건을 들고 있는 사람</b>.
     *
     * <p><b>2026-09-10 (`UR-260910-4` §L-3) — 규칙을 뒤집었다.</b> 예전에는
     * {@code findByRole("SDE")} 로 <b>역할을 걸러</b> 행을 만들었다. 역할로 거르는 한
     * <b>합계는 영원히 안 맞는다</b> — 리더 본인도(실측 활성 21건, 팀 최다), 담당자로 잡히는
     * SME 본인도(21건) 표에서 빠졌다. <b>리더 화면 45 vs 20 · SME 화면 52 vs 20.</b>
     * 상태 분포와 이 표가 서로 다른 카드라 아무도 눈치채지 못했고, 한 표로 합치면 즉시 드러난다.
     * 그래서 역할 목록을 아예 쓰지 않는다 — {@code POOL_ROLES}·{@code TIER_ROLES} 와 또 어긋날 자리를 남기지 않는다.
     *
     * <p><b>묶는 키는 담당자 이름이다.</b> ITSM 목록이 담당자 ID 를 주지 않아({@code assigneePerId} 는 전 건 null)
     * 다른 키가 없다. ⚠️ 사람마다 순회하며 이름·ID 로 걸러내던 예전 방식은 <b>동명이인이면 같은 건을 두 번 센다</b> —
     * 먼저 묶고 나중에 사람을 붙이면 그 구멍이 사라진다(가입자는 아바타 색에만 쓴다).
     *
     * <p><b>{@code zeroRowTeam} 이 있으면 그 팀 가입자는 0건이라도 남긴다.</b> 두 역할이 이 표에
     * 서로 다른 질문을 하기 때문이다 —
     * <ul>
     *   <li><b>리더</b>는 "누가 비어 있나" 를 본다. <b>0건인 팀원이 빠지면 안 된다</b>(배분이 이 표에서 난다).</li>
     *   <li><b>SME</b>(2026-09-10 추가)는 "우리 법인 건을 누가 맡고 있나" 를 본다. {@code null} 이다.
     *       0건인 사람은 <b>우리 법인과 무관한 다른 팀 SDE</b> 이고, 그 이름이 뜨면
     *       "이 사람이 우리 건을 놀리고 있다" 로 읽힌다.</li>
     * </ul>
     * ⚠️ 화면에서 거르지 않고 <b>여기서 뺀다</b> — 응답에 실리면 개발자도구로 보이고,
     * SME 와 무관한 SDE 의 이름은 애초에 나갈 이유가 없다(분석 노트 초안과 같은 규칙).
     *
     * <p><b>완료 건은 세지 않는다.</b> 대시보드 목록이 이미 완료를 빼고 건수는 KPI 카드에 있다.
     * 그래서 <b>{@code dist} 의 DONE 을 제외한 합 == 이 표의 합</b> 이어야 한다 —
     * {@code DashboardServiceScopeTest.담당자표의_합은_상태분포와_일치한다} 가 그것을 고정한다.
     * 그 등식이 성립하려면 <b>주인 없는 건도 행이 있어야 한다</b> → {@link #UNASSIGNED}.
     * 완료도 같은 등식을 지킨다 — <b>{@code dist} 의 DONE == 행들의 {@code done} 합</b>.
     */
    private List<TeamView> team(List<ItsmRequest> all, LocalDate today, String zeroRowTeam) {
        // ⚠️ **완료 건도 묶는다**(2026-09-11). 표의 상태 열·합계는 여전히 진행 중만 세지만,
        //    담당자별 완료 건수를 따로 보여 주려면 여기서 빠뜨리면 안 된다 — 완료만 있는 사람은
        //    행 자체가 안 생겨 그 사람의 완료 건수가 통째로 사라진다(전체 줄과도 안 맞게 된다).
        Map<String, List<ItsmRequest>> byHolder = new LinkedHashMap<>();
        for (ItsmRequest r : all)
            byHolder.computeIfAbsent(holderKey(r), k -> new ArrayList<>()).add(r);
        List<ItsmRequest> orphans = byHolder.remove(UNASSIGNED);   // 맨 위에 따로 세운다

        // 이름 → 가입자. **집계에는 쓰지 않는다** — 아바타 색(`perId`)을 붙이기 위해서만 본다.
        Map<String, AppUser> registered = new HashMap<>();
        for (AppUser u : userRepo.findAll())
            if (u.getName() != null) registered.putIfAbsent(u.getName(), u);

        List<TeamView> team = new ArrayList<>();
        byHolder.forEach((name, rows) -> {
            AppUser u = registered.get(name);
            String perId = u != null && u.getItsmPerId() != null ? u.getItsmPerId() : rows.get(0).getAssigneePerId();
            team.add(teamRow(perId, name, rows, today));
        });
        team.sort(Comparator.comparingInt(TeamView::active).reversed());   // 많이 든 사람이 위로

        if (zeroRowTeam != null) {
            Set<String> placed = team.stream().map(TeamView::name).collect(Collectors.toCollection(HashSet::new));
            for (AppUser u : userRepo.findByTeam(zeroRowTeam))
                if (u.getName() != null && placed.add(u.getName()))
                    team.add(teamRow(u.getItsmPerId(), u.getName(), List.of(), today));   // 0건 팀원은 아래로
        }

        // ⚠️ 미배정은 **맨 위**다. 아무도 안 들고 있는 건이라 리더가 가장 먼저 볼 줄이고,
        //    이 줄이 없으면 합계가 조용히 안 맞는다(주인 없는 건이 어느 행에도 안 들어간다).
        if (orphans != null) team.add(0, teamRow(null, UNASSIGNED, orphans, today));
        return team;
    }

    /** 담당자 이름이 없는 건도 어딘가에는 서야 한다 — 안 그러면 표 합계가 조용히 모자란다. */
    private static String holderKey(ItsmRequest r) {
        String n = r.getAssigneeName();
        return n == null || n.isBlank() ? UNASSIGNED : n;
    }

    /**
     * {@code rows} 는 그 사람의 건 전부다(완료 포함, 빈 목록 = 0건 팀원).
     *
     * <p><b>상태 열과 {@code active} 는 진행 중만 센다. 완료는 {@code done} 으로 따로 나간다</b>(2026-09-11).
     * 합계에 섞으면 "지금 몇 건 들고 있나" 를 못 읽는다 — 누적 완료가 많은 사람일수록 크게 보인다.
     *
     * <p>⚠️ 상태 열의 순서·구성은 {@link #ROW_ORDER} 하나에서 온다. 예전에는 여기가
     * {@code ANAL,DEV,TEST,DEP,NEW,HOLD} 순이고 화면(`TeamList`)이 {@code DEV,TEST,DEP,ANAL,HOLD} 순이라
     * <b>둘이 달랐고, 화면 쪽에 NEW 가 아예 없어 막대에서 통째로 빠졌다</b> — 숫자에는 들어가는데도.
     * 실측으로 리더 화면 5줄 중 3줄이 <b>빈 막대에 숫자만</b> 있었다(`UR-260910-3` ⓒ).
     */
    private TeamView teamRow(String perId, String name, List<ItsmRequest> rows, LocalDate today) {
        List<ItsmRequest> open = rows.stream().filter(r -> !BoardStatus.DONE.equals(r.getWorkStatus())).toList();
        int lateN = (int) open.stream().filter(r -> RequestDerivation.late(r, today)).count();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : ROW_ORDER) byStatus.put(s, (int) open.stream().filter(r -> s.equals(r.getWorkStatus())).count());
        return new TeamView(perId, name, open.size(), lateN, rows.size() - open.size(), byStatus);
    }

    public List<TimelineView> timeline(UserSession session, int limit) {
        Set<String> scope = scopeReqNos(session);
        List<RequestStatusHistory> rows = histRepo.findByOrderByObservedAtDesc(PageRequest.of(0, Math.max(limit * 5, limit)))
                .stream().filter(h -> scope.contains(h.getReqNo())).limit(limit).toList();
        Map<String, String> titles = reqRepo.findAll().stream()
                .collect(Collectors.toMap(ItsmRequest::getReqNo, r -> r.getTitle() == null ? "" : r.getTitle(), (a, b) -> a));
        return rows.stream()
                .map(h -> new TimelineView(h.getReqNo(), titles.getOrDefault(h.getReqNo(), ""),
                        h.getActorName(), h.getFromStatus(), h.getToStatus(), h.getToStaNm(), h.getObservedAt()))
                .collect(Collectors.toList());
    }

    private Map<String, Integer> attachmentCounts() {
        Map<String, Integer> m = new HashMap<>();
        for (AttachmentRef a : attRepo.findAll()) m.merge(a.getReqNo(), 1, Integer::sum);
        return m;
    }

    /**
     * '신규 유입' 판정 — 우리 시스템에 처음 나타난 지 `app.new-request-hours` 안인가.
     *
     * <p>기준을 <b>최초 관측</b>으로 잡는 이유: ITSM 의 등록일(`reqDt`)은 현업이 올린 날이라
     * 우리가 뒤늦게 미러링하면 처음부터 '오래된 건'이 되어 버린다. 화면이 답할 질문은
     * "언제 접수됐나" 가 아니라 <b>"내가 아직 못 본 건인가"</b> 다.
     */
    private boolean isFresh(LocalDateTime firstSeen) {
        return firstSeen != null && firstSeen.isAfter(AppTime.now().minusHours(newRequestHours));
    }

    /**
     * 목록 배지 — `노트` / `노트 갱신`.
     *
     * <p>`updated` 는 <b>보는 사람마다 다른 사실</b>이다: <b>내가 마지막으로 본 뒤에 공유됐는가.</b>
     * 그래서 "최근 N시간" 같은 시간 근사치를 쓰지 않는다 — 그러면 방금 읽은 사람에게도 배지가 뜨고
     * (거짓 양성) 오래 자리를 비운 사람에게는 안 뜬다(거짓 음성). 둘 다 목적을 놓친다.
     *
     * <p>⚠️ <b>한 번도 안 본 노트는 `갱신` 이 아니다</b> — 그 사람에게는 아직 '새 노트' 다.
     * 갱신은 "내가 본 것과 다르다" 는 뜻이라 본 적이 있어야 성립한다.
     */
    private Map<String, NoteBadge> noteBadges(Set<String> reqNos, Long userId) {
        if (reqNos.isEmpty()) return Map.of();
        List<String> keys = List.copyOf(reqNos);
        Map<String, LocalDateTime> readAt = userId == null ? Map.of()
                : noteReadRepo.findByUserIdAndReqNoIn(userId, keys).stream()
                        .collect(Collectors.toMap(NoteRead::getReqNo, NoteRead::getReadAt, (a, b) -> a));

        Map<String, NoteBadge> out = new HashMap<>();
        for (RequestNote n : noteRepo.findByReqNoIn(keys)) {
            // 빈 노트 행에 배지를 붙이면 SDE 가 헛걸음한다
            if (n.getBodyText() == null || n.getBodyText().isBlank()) continue;
            LocalDateTime mine = readAt.get(n.getReqNo());
            LocalDateTime published = n.getPublishedAt();
            boolean updated = mine != null && published != null && published.isAfter(mine);
            out.put(n.getReqNo(), new NoteBadge(true, updated));
        }
        return out;
    }

    /**
     * 목록 배지 — `새 댓글 N`.
     *
     * <p><b>내가 볼 수 있는 채널만 센다</b>(역할 기준). 리더 목록에서 `SME↔SDE` 대화가 배지로 세어지면
     * 내용은 못 보면서 <b>"저 건에 무슨 말이 오갔다" 는 사실만 새어 나간다</b> — 응답에서 빼는 것과 같은 이유로 막는다.
     *
     * <p><b>내가 쓴 글은 안 센다.</b> 방금 쓴 내 글에 배지가 붙으면 사람이 배지를 안 믿게 된다.
     *
     * <p>⚠️ 채널 목록의 출처는 {@code RequestCommentService.channelsForRole} <b>하나</b>다.
     * 여기에 한 벌 더 적으면 <b>배지는 뜨는데 열면 아무것도 없는</b> 상태가 조용히 생긴다.
     */
    private Map<String, Integer> commentBadges(Set<String> reqNos, Long userId) {
        if (reqNos.isEmpty() || userId == null) return Map.of();
        AppUser me = userRepo.findById(userId).orElse(null);
        if (me == null) return Map.of();
        Set<String> mine = RequestCommentService.channelsForRole(me.getRole());
        if (mine.isEmpty()) return Map.of();

        List<String> keys = List.copyOf(reqNos);
        Map<String, LocalDateTime> readAt = new HashMap<>();
        for (CommentRead r : commentReadRepo.findByUserIdAndReqNoIn(userId, keys))
            readAt.put(r.getReqNo() + ' ' + r.getChannel(), r.getReadAt());

        Map<String, Integer> out = new HashMap<>();
        for (RequestComment c : commentRepo.findByReqNoIn(keys)) {
            if (!mine.contains(c.getChannel())) continue;
            if (userId.equals(c.getAuthorId())) continue;
            LocalDateTime seen = readAt.get(c.getReqNo() + ' ' + c.getChannel());
            // ⚠️ 같은 초는 '읽음' 으로 친다(AppTime 이 초 단위라 구분이 안 된다).
            //    반대로 하면 탭을 열어도 배지가 안 꺼지는 경우가 생겨 배지를 아예 못 믿게 된다.
            if (seen != null && !c.getCreatedAt().isAfter(seen)) continue;
            out.merge(c.getReqNo(), 1, Integer::sum);
        }
        return out;
    }

    /** 목록 한 줄의 노트 배지 상태. `updated` = **내가 본 뒤에 다시 공유됐다.** */
    private record NoteBadge(boolean has, boolean updated) {
        static final NoteBadge NONE = new NoteBadge(false, false);
    }

    private RequestView toDto(ItsmRequest r, LocalDate today, int attachmentCount, TodoState todo,
                             String tier, NoteBadge note, int unreadComments,
                             ScheduleInfo schedule, int fileCount) {
        return new RequestView(
                r.getReqNo(), r.getTitle(), r.getRequester(),
                RequestDerivation.dept(r), RequestDerivation.requesterName(r.getRequester()),
                r.getBody(), r.getCompCd(), r.getReqCompNm(), r.getReqCatNm(),
                r.getReqTypCd(), r.getReqTypNm(),
                r.getLeaderPerId(), r.getLeaderName(), r.getAssigneePerId(), r.getAssigneeName(),
                r.getAssignStage(), r.getWorkStatus(), r.getItsmStaCd(), r.getItsmStaNm(),
                r.getWorkType(), r.getWorkNo(),
                r.getReqDt() == null ? null : r.getReqDt().toLocalDate(),
                RequestDerivation.dueDate(r), attachmentCount,
                RequestDerivation.late(r, today), RequestDerivation.soon(r, today),
                todo.inTodo(), todo.inTodo() ? null : todo.lastSeen(),
                RequestDerivation.reqType(r), RequestDerivation.targetSystem(r), RequestDerivation.priority(r, today),
                r.getDoneAt(), tier, note.has(), note.updated(), unreadComments,
                todo.firstSeen(), isFresh(todo.firstSeen()),
                schedule, fileCount, r.getCompletedManuallyAt() != null);
    }
}
