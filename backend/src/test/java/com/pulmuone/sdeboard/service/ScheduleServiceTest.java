package com.pulmuone.sdeboard.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * **일정이 지켜야 하는 것들**(`UR-260922-1`) — 상태 전이, 권한, 사유, 이력.
 *
 * 이 규칙들이 흔들리면 화면에서는 알아챌 방법이 없다: 일정이 있는데 대기로 남거나, 취소했는데 작업중으로 남거나,
 * SDE 가 남의 일정을 취소하는 일이 조용히 일어난다.
 */
class ScheduleServiceTest {

    private static final String REQ = "CSD260922000001";
    private static final long SME = 1, LEADER = 2, SDE_A = 3, SDE_B = 4, OUTSIDER = 9;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 22, 9, 0);

    private RequestScheduleRepository scheduleRepo;
    private ItsmRequestRepository reqRepo;
    private RequestStatusHistoryRepository histRepo;
    private AppUserRepository userRepo;
    private DashboardService dashboard;
    private ScheduleService svc;

    private ItsmRequest req;
    private final List<RequestSchedule> stored = new ArrayList<>();

    private static AppUser user(long id, String role) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setLoginId("u" + id); u.setName("사용자" + id);
        return u;
    }

    private static UserSession session(long id, String role) {
        UserSession s = new UserSession();
        s.setUserId(id); s.setRole(role); s.setName("사용자" + id);
        return s;
    }

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(RequestScheduleRepository.class);
        reqRepo = mock(ItsmRequestRepository.class);
        histRepo = mock(RequestStatusHistoryRepository.class);
        userRepo = mock(AppUserRepository.class);
        dashboard = mock(DashboardService.class);
        svc = new ScheduleService(scheduleRepo, reqRepo, histRepo, userRepo, dashboard);

        req = new ItsmRequest();
        req.setReqNo(REQ);
        req.setTitle("발주 화면 오류");
        req.setWorkStatus(BoardStatus.WAITING);

        List<AppUser> everyone = List.of(user(SME, "SME"), user(LEADER, "SDE_LEADER"), user(SDE_A, "SDE"), user(SDE_B, "SDE"), user(OUTSIDER, "SDE"));
        for (AppUser u : everyone) lenient().when(userRepo.findById(u.getId())).thenReturn(Optional.of(u));
        lenient().when(userRepo.findAllById(any())).thenReturn(everyone);

        lenient().when(dashboard.requireInScopeRequest(any(), eq(REQ))).thenReturn(req);
        lenient().when(dashboard.visibleReqNos(any())).thenReturn(Set.of(REQ));
        // SME·리더의 범위 = SDE A·B (OUTSIDER 는 범위 밖)
        lenient().when(dashboard.scopeMembersOf(any())).thenReturn(List.of(user(SDE_A, "SDE"), user(SDE_B, "SDE"), user(LEADER, "SDE_LEADER"), user(SME, "SME")));
        lenient().when(reqRepo.findByReqNo(REQ)).thenReturn(Optional.of(req));

        lenient().when(scheduleRepo.save(any(RequestSchedule.class))).thenAnswer(inv -> {
            RequestSchedule x = inv.getArgument(0);
            if (x.getId() == null) { x.setId((long) (stored.size() + 1)); stored.add(x); }
            return x;
        });
        lenient().when(scheduleRepo.findById(any())).thenAnswer(inv -> stored.stream().filter(x -> x.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(scheduleRepo.findFirstByReqNoAndStatus(eq(REQ), eq(RequestSchedule.ACTIVE)))
                .thenAnswer(inv -> stored.stream().filter(x -> RequestSchedule.ACTIVE.equals(x.getStatus())).findFirst());
    }

    private ScheduleView createAs(long id, String role, Long assignee) {
        return svc.create(session(id, role), new ScheduleCreateRequest(REQ, T0, T0.plusHours(4), assignee));
    }

    private static void assertStatus(ResponseStatusException e, HttpStatus s) { assertThat(e.getStatusCode()).isEqualTo(s); }

    // ── 등록 ──

    @Test
    void SDE_가_본인_일정을_등록하면_대기가_작업중이_된다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);

        assertThat(v.assigneeId()).isEqualTo(SDE_A);
        assertThat(v.createdBy()).isEqualTo(SDE_A);
        assertThat(v.status()).isEqualTo(RequestSchedule.ACTIVE);
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);
        ArgumentCaptor<RequestStatusHistory> h = ArgumentCaptor.forClass(RequestStatusHistory.class);
        verify(histRepo).save(h.capture());
        assertThat(h.getValue().getFromStatus()).isEqualTo(BoardStatus.WAITING);
        assertThat(h.getValue().getToStatus()).isEqualTo(BoardStatus.IN_PROGRESS);
    }

    @Test
    void 담당자_표시는_바뀌지_않는다() {
        req.setAssigneeName("ITSM 담당자");
        createAs(SDE_A, "SDE", null);
        assertThat(req.getAssigneeName()).isEqualTo("ITSM 담당자");     // 사용자 결정 §8-5
    }

    @Test
    void SME_는_범위_안_SDE_에게_할당할_수_있다() {
        ScheduleView v = createAs(SME, "SME", SDE_B);
        assertThat(v.assigneeId()).isEqualTo(SDE_B);
        assertThat(v.createdBy()).isEqualTo(SME);          // 등록자와 작업자가 다르다
    }

    @Test
    void 리더도_SDE_에게_할당할_수_있고_본인_직접처리도_된다() {
        assertThat(createAs(LEADER, "SDE_LEADER", SDE_A).assigneeId()).isEqualTo(SDE_A);
        stored.clear(); req.setWorkStatus(BoardStatus.WAITING);
        assertThat(createAs(LEADER, "SDE_LEADER", null).assigneeId()).isEqualTo(LEADER);
    }

    @Test
    void SDE_는_남을_작업자로_지정할_수_없다() {
        assertThatThrownBy(() -> createAs(SDE_A, "SDE", SDE_B))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.WAITING);
    }

    @Test
    void 조회_범위_밖_사람은_작업자로_지정할_수_없다() {
        assertThatThrownBy(() -> createAs(SME, "SME", OUTSIDER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void 종료_시각은_시작_시각_뒤여야_하고_60일을_넘지_못한다() {
        assertThatThrownBy(() -> svc.create(session(SDE_A, "SDE"), new ScheduleCreateRequest(REQ, T0, T0, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.create(session(SDE_A, "SDE"), new ScheduleCreateRequest(REQ, T0, T0.plusDays(61), null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.create(session(SDE_A, "SDE"), new ScheduleCreateRequest(REQ, null, T0, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 이미_일정이_있으면_또_등록할_수_없다() {
        createAs(SDE_A, "SDE", null);
        assertThatThrownBy(() -> createAs(SDE_A, "SDE", null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.CONFLICT));
        assertThat(stored).hasSize(1);
    }

    @Test
    void 작업완료와_추적불가_요청에는_일정을_잡지_못한다() {
        for (String closed : List.of(BoardStatus.DONE, BoardStatus.UNTRACKED)) {
            req.setWorkStatus(closed);
            assertThatThrownBy(() -> createAs(SDE_A, "SDE", null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.CONFLICT));
        }
        assertThat(stored).isEmpty();
    }

    // ── 수정 ──

    @Test
    void 수정은_사유가_필수다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        for (String reason : new String[]{null, "", "   "})
            assertThatThrownBy(() -> svc.update(session(SDE_A, "SDE"), v.id(), new ScheduleUpdateRequest(T0.plusDays(1), T0.plusDays(1).plusHours(2), null, reason)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void 바뀐_내용이_없으면_수정할_수_없다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        assertThatThrownBy(() -> svc.update(session(SDE_A, "SDE"), v.id(), new ScheduleUpdateRequest(T0, T0.plusHours(4), null, "그냥")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test
    void 수정하면_옛_일정은_사유와_함께_남고_새_일정이_유효해진다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        ScheduleView nv = svc.update(session(SDE_A, "SDE"), v.id(),
                new ScheduleUpdateRequest(T0.plusDays(1), T0.plusDays(1).plusHours(3), null, "고객 일정 변경"));

        RequestSchedule old = stored.get(0);
        assertThat(old.getStatus()).isEqualTo(RequestSchedule.REVISED);
        assertThat(old.getReason()).isEqualTo("고객 일정 변경");
        assertThat(old.getClosedBy()).isEqualTo(SDE_A);
        assertThat(nv.status()).isEqualTo(RequestSchedule.ACTIVE);
        assertThat(stored.get(1).getPrevId()).isEqualTo(old.getId());
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);      // 수정은 상태를 바꾸지 않는다
        assertThat(stored.stream().filter(x -> RequestSchedule.ACTIVE.equals(x.getStatus()))).hasSize(1);
    }

    // ── 취소 ──

    @Test
    void 취소는_사유가_필수이고_취소하면_대기로_돌아간다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        assertThatThrownBy(() -> svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest(" ")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);

        svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest("다른 담당자에게 넘김"));

        assertThat(stored.get(0).getStatus()).isEqualTo(RequestSchedule.CANCELLED);
        assertThat(stored.get(0).getReason()).isEqualTo("다른 담당자에게 넘김");
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.WAITING);
    }

    @Test
    void 취소_뒤에는_새로_등록할_수_있다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest("넘김"));
        ScheduleView again = createAs(SDE_B, "SDE", null);
        assertThat(again.assigneeId()).isEqualTo(SDE_B);
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);
    }

    @Test
    void 이미_취소된_일정은_다시_취소할_수_없다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest("넘김"));
        assertThatThrownBy(() -> svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest("또")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void 남의_일정은_SDE_가_취소할_수_없다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        assertThatThrownBy(() -> svc.cancel(session(SDE_B, "SDE"), v.id(), new ScheduleCancelRequest("장난")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.NOT_FOUND));
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);
    }

    @Test
    void 범위_안_리더는_팀원_일정을_취소할_수_있다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        svc.cancel(session(LEADER, "SDE_LEADER"), v.id(), new ScheduleCancelRequest("재배정"));
        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.WAITING);
    }

    @Test
    void 종료된_요청의_일정은_바꾸거나_취소할_수_없다() {
        ScheduleView v = createAs(SDE_A, "SDE", null);
        req.setWorkStatus(BoardStatus.DONE);          // ITSM 에서 사라져 작업완료가 됨
        assertThatThrownBy(() -> svc.cancel(session(SDE_A, "SDE"), v.id(), new ScheduleCancelRequest("늦은 취소")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> svc.update(session(SDE_A, "SDE"), v.id(), new ScheduleUpdateRequest(T0.plusDays(1), T0.plusDays(1).plusHours(1), null, "늦은 수정")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertStatus(e, HttpStatus.CONFLICT));
    }

    // ── 캘린더 · 후보 ──

    @Test
    void 캘린더는_범위_밖이어도_내가_작업자인_일정은_보여준다() {
        RequestSchedule mine = new RequestSchedule();
        mine.setId(10L); mine.setReqNo("OTHER-1"); mine.setAssigneeId(SDE_A); mine.setCreatedBy(LEADER);
        mine.setStartDt(T0); mine.setEndDt(T0.plusHours(2)); mine.setStatus(RequestSchedule.ACTIVE); mine.setCreatedAt(T0);
        RequestSchedule notMine = new RequestSchedule();
        notMine.setId(11L); notMine.setReqNo("OTHER-2"); notMine.setAssigneeId(SDE_B); notMine.setCreatedBy(LEADER);
        notMine.setStartDt(T0); notMine.setEndDt(T0.plusHours(2)); notMine.setStatus(RequestSchedule.ACTIVE); notMine.setCreatedAt(T0);
        when(scheduleRepo.findByStatusAndStartDtLessThanAndEndDtGreaterThan(eq(RequestSchedule.ACTIVE), any(), any()))
                .thenReturn(List.of(mine, notMine));
        when(reqRepo.findByReqNoIn(any())).thenReturn(List.of());

        CalendarResponse c = svc.calendar(session(SDE_A, "SDE"), T0.minusDays(1), T0.plusDays(30));

        assertThat(c.items()).extracting(ScheduleView::id).containsExactly(10L);
    }

    @Test
    void 캘린더_조회_기간이_너무_길면_거절한다() {
        assertThatThrownBy(() -> svc.calendar(session(SDE_A, "SDE"), T0, T0.plusDays(120)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 작업자_후보는_SDE_는_본인뿐이고_SME_는_범위_안_SDE_를_포함한다() {
        assertThat(svc.assignees(session(SDE_A, "SDE"))).extracting(AssigneeOption::id).containsExactly(SDE_A);
        List<AssigneeOption> forSme = svc.assignees(session(SME, "SME"));
        assertThat(forSme).extracting(AssigneeOption::id).contains(SME, SDE_A, SDE_B, LEADER).doesNotContain(OUTSIDER);
        assertThat(forSme.get(0).me()).isTrue();               // 본인이 맨 위
    }
}
