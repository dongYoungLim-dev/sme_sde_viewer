package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.*;
import com.pulmuone.sdeboard.repo.*;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.RequestDtos.RequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * `UR-260911-2` — "신규 유입" 이 **세션 사용자(나) 기준**인지 지킨다.
 *
 * <p>실측(운영 DB, 2026-09-14): 리더가 접수 단계에서 먼저 훑어본 뒤 시차를 두고 SDE 에게 넘어간 건이
 * SDE 화면에서 **방금 왔는데도 곧장 '진행 중'** 으로 보였다 — `firstSeen` 이 <b>전체 소유자 중 최솟값</b>이라
 * 리더가 먼저 본 시각(24시간 전)을 물려받았기 때문이다. 화면 설계 의도(`DashboardPage.jsx`)는
 * "내가 아직 못 본 건인가" 인데 구현은 "조직 전체가 언제 처음 봤나" 를 재고 있었다.
 */
class DashboardServiceFreshnessTest {

    private static final long ME_ID = 1, LEADER_ID = 2;

    private AppUserRepository userRepo;
    private RequestOwnerRepository ownerRepo;
    private ItsmRequestRepository reqRepo;
    private DashboardService svc;


    private static AppUser user(long id, String role) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setLoginId("u" + id); u.setName("사용자" + id);
        return u;
    }

    private static ItsmRequest req(String reqNo) {
        ItsmRequest r = new ItsmRequest();
        r.setReqNo(reqNo); r.setReqCompNm("풀무원푸드앤컬처");
        r.setItsmStaCd("00566"); r.setItsmStaNm("변경접수"); r.setWorkStatus(BoardStatus.WAITING);
        r.setTitle("제목 " + reqNo);
        return r;
    }

    private static RequestOwner owner(long userId, String reqNo, LocalDateTime firstSeen) {
        RequestOwner o = new RequestOwner();
        o.setUserId(userId); o.setReqNo(reqNo); o.setActive(true);
        o.setFirstSeen(firstSeen); o.setLastSeen(LocalDateTime.now());
        return o;
    }

    private final AppUser me = user(ME_ID, "SDE");
    private final ItsmRequest reqA = req("CSD-A");

    @BeforeEach
    void setUp() {
        reqRepo = mock(ItsmRequestRepository.class);
        userRepo = mock(AppUserRepository.class);
        ownerRepo = mock(RequestOwnerRepository.class);
        SdeAssignmentRepository poolRepo = mock(SdeAssignmentRepository.class);
        AttachmentRefRepository attRepo = mock(AttachmentRefRepository.class);
        RequestStatusHistoryRepository histRepo = mock(RequestStatusHistoryRepository.class);
        SessionRegistry sessions = mock(SessionRegistry.class);
        RequestNoteRepository noteRepo = mock(RequestNoteRepository.class);
        NoteReadRepository noteReadRepo = mock(NoteReadRepository.class);
        RequestCommentRepository commentRepo = mock(RequestCommentRepository.class);
        CommentReadRepository commentReadRepo = mock(CommentReadRepository.class);
        lenient().when(noteRepo.findByReqNoIn(any())).thenReturn(List.of());
        lenient().when(noteReadRepo.findByUserIdAndReqNoIn(any(), any())).thenReturn(List.of());
        lenient().when(commentRepo.findByReqNoIn(any())).thenReturn(List.of());
        lenient().when(commentReadRepo.findByUserIdAndReqNoIn(any(), any())).thenReturn(List.of());

        svc = new DashboardService(reqRepo, histRepo, attRepo, userRepo, ownerRepo, poolRepo, noteRepo,
                noteReadRepo, commentRepo, commentReadRepo, sessions, new ItsmProperties(),
                mock(RequestScheduleRepository.class), mock(RequestFileRepository.class));
        // yml `app.new-request-hours` 기본값(24) — 스프링 컨테이너 밖 단위테스트라 직접 채운다
        ReflectionTestUtils.setField(svc, "newRequestHours", 24);

        lenient().when(userRepo.findById(ME_ID)).thenReturn(Optional.of(me));
        lenient().when(userRepo.findAll()).thenReturn(List.of(me));
        lenient().when(reqRepo.findAll()).thenReturn(List.of(reqA));
        lenient().when(attRepo.findAll()).thenReturn(List.of());
        lenient().when(poolRepo.findAll()).thenReturn(List.of());
        // 본인 소유 행만으로 조회 범위가 잡힌다 (SDE 는 자기 자신만 범위 — scopeMembers)
        lenient().when(ownerRepo.findByUserIdIn(any())).thenReturn(List.of(owner(ME_ID, "CSD-A", LocalDateTime.now())));
    }

    private UserSession session() {
        UserSession s = new UserSession();
        s.setUserId(ME_ID); s.setRole("SDE"); s.setLoginId("u1");
        return s;
    }

    @Test
    void 리더가_먼저_봤어도_내게_방금_왔으면_신규다() {
        // 리더(LEADER_ID)가 이틀 전 먼저 본 건이 방금 내 To-Do 에 들어왔다 — 실측 그대로 재현
        when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(LEADER_ID, "CSD-A", LocalDateTime.now().minusDays(2)),
                owner(ME_ID, "CSD-A", LocalDateTime.now())));

        List<RequestView> rows = svc.list(session(), "ALL");

        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .singleElement()
                .satisfies(r -> assertThat(r.fresh()).isTrue());
    }

    @Test
    void 내가_직접_본_지_24시간이_지나면_신규가_아니다() {
        when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(ME_ID, "CSD-A", LocalDateTime.now().minusHours(25))));

        List<RequestView> rows = svc.list(session(), "ALL");

        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .singleElement()
                .satisfies(r -> assertThat(r.fresh()).isFalse());
    }

    @Test
    void 남이_먼저_봤고_나는_소유_행이_없으면_신규가_아니다() {
        // SME 의 법인 기준 조회처럼 내 소유 행이 없는 경우 — 전과 동일하게 신규로 뜨지 않아야 한다(회귀 방지)
        when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(LEADER_ID, "CSD-A", LocalDateTime.now())));

        List<RequestView> rows = svc.list(session(), "ALL");

        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .singleElement()
                .satisfies(r -> assertThat(r.fresh()).isFalse());
    }
}
