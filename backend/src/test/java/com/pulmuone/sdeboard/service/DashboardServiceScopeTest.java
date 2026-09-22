package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.RequestDtos.*;
import com.pulmuone.sdeboard.web.dto.StatsDtos.DistView;
import com.pulmuone.sdeboard.web.dto.StatsDtos.StatsResponse;
import com.pulmuone.sdeboard.web.dto.StatsDtos.TeamView;

import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.*;
import com.pulmuone.sdeboard.domain.RequestComment;
import com.pulmuone.sdeboard.repo.*;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §F — **SME 는 자기 법인 요청건을 전부 본다.**
 *
 * <p>여기서 지키는 것 —
 * <ul>
 *   <li>① 조회 기준이 <b>소유자가 아니라 요청의 법인</b>(`req_comp_nm`)이다.
 *       도입 전에 SDE 에게 넘어가 지금도 그 사람 To-Do 에 살아 있는 건이 SME 화면에 나타나야 한다.</li>
 *   <li>② 법인명 비교는 <b>공백을 무시</b>한다. ITSM 이 법인코드를 주지 않아 문자열로 맞출 수밖에 없고,
 *       가입 때 손으로 적은 값과 비교하므로 표기 하나로 <b>조용히 0건</b>이 되면 안 된다.</li>
 *   <li>③ <b>다른 법인 건은 섞이지 않는다.</b> SDE 는 여러 법인을 담당하므로, 그 사람의 To-Do 를
 *       통째로 넣는 방식(인력풀 기준)이었다면 남의 법인 요청이 새어 들어온다.</li>
 *   <li>④ `inTodo` 는 <b>전체 소유자</b> 기준이다. SDE 가 처리 중인 건이 SME 화면에서
 *       'ITSM 할 일 종료'로 보이면 안 된다.</li>
 * </ul>
 */
class DashboardServiceScopeTest {

    private static final String MY_CORP = "풀무원푸드앤컬처";
    private static final long SME_ID = 1, SDE_ID = 2;

    private ItsmRequestRepository reqRepo;
    private AppUserRepository userRepo;
    private RequestOwnerRepository ownerRepo;
    private SdeAssignmentRepository poolRepo;
    private RequestNoteRepository noteRepo;
    private NoteReadRepository noteReadRepo;
    private RequestCommentRepository commentRepo;
    private CommentReadRepository commentReadRepo;
    private DashboardService svc;


    private static AppUser user(long id, String role, String corpNm) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setCorpNm(corpNm); u.setLoginId("u" + id); u.setName("사용자" + id);
        if ("SME".equals(role)) u.setCorpCd("00013");
        return u;
    }

    private static ItsmRequest req(String reqNo, String corpNm) {
        ItsmRequest r = new ItsmRequest();
        r.setReqNo(reqNo); r.setReqCompNm(corpNm);
        r.setItsmStaCd("00566"); r.setItsmStaNm("변경접수"); r.setWorkStatus(BoardStatus.WAITING);
        r.setTitle("제목 " + reqNo); r.setAssigneeName("임동영");
        return r;
    }

    /** `systemNm == null` = 법인담당SDE(차수 없음). 차수는 시스템 줄에만 있다 (2026-09-10). */
    private static SdeAssignment assignment(String corpNm, String systemNm, Integer tier, long userId) {
        SdeAssignment a = new SdeAssignment();
        a.setCorpNm(corpNm); a.setSystemNm(systemNm); a.setTier(tier); a.setUserId(userId);
        return a;
    }

    private static RequestOwner owner(long userId, String reqNo, boolean active) {
        RequestOwner o = new RequestOwner();
        o.setUserId(userId); o.setReqNo(reqNo); o.setActive(active); o.setLastSeen(LocalDateTime.now());
        return o;
    }

    private final AppUser sme = user(SME_ID, "SME", MY_CORP);
    private final AppUser sde = user(SDE_ID, "SDE", null);

    /** 도입 전 SDE 에게 넘어가 지금도 그 사람 To-Do 에 살아 있는 건 — SME 는 소유 행이 없다 */
    private final ItsmRequest inflightWithSde = req("CSD-A", MY_CORP);
    /** 법인명 표기만 다른 우리 법인 건 */
    private final ItsmRequest spaced = req("CSD-B", "풀무원 푸드앤컬처");
    /** 남의 법인 건 — 같은 SDE 가 들고 있다 */
    private final ItsmRequest otherCorp = req("CSD-C", "풀무원식품");

    @BeforeEach
    void setUp() {
        reqRepo = mock(ItsmRequestRepository.class);
        userRepo = mock(AppUserRepository.class);
        ownerRepo = mock(RequestOwnerRepository.class);
        poolRepo = mock(SdeAssignmentRepository.class);
        AttachmentRefRepository attRepo = mock(AttachmentRefRepository.class);
        RequestStatusHistoryRepository histRepo = mock(RequestStatusHistoryRepository.class);
        SessionRegistry sessions = mock(SessionRegistry.class);

        noteRepo = mock(RequestNoteRepository.class);
        lenient().when(noteRepo.findByReqNoIn(any())).thenReturn(List.of());
        noteReadRepo = mock(NoteReadRepository.class);
        lenient().when(noteReadRepo.findByUserIdAndReqNoIn(any(), any())).thenReturn(List.of());
        commentRepo = mock(RequestCommentRepository.class);
        lenient().when(commentRepo.findByReqNoIn(any())).thenReturn(List.of());
        commentReadRepo = mock(CommentReadRepository.class);
        lenient().when(commentReadRepo.findByUserIdAndReqNoIn(any(), any())).thenReturn(List.of());
        svc = new DashboardService(reqRepo, histRepo, attRepo, userRepo, ownerRepo, poolRepo, noteRepo,
                noteReadRepo, commentRepo, commentReadRepo, sessions, new ItsmProperties(),
                mock(RequestScheduleRepository.class), mock(RequestFileRepository.class));

        lenient().when(userRepo.findById(SME_ID)).thenReturn(Optional.of(sme));
        lenient().when(userRepo.findAll()).thenReturn(List.of(sme, sde));
        lenient().when(userRepo.findByRoleAndCorpCd("SME", "00013")).thenReturn(List.of(sme));
        lenient().when(userRepo.findAllById(any())).thenReturn(List.of(sde));
        lenient().when(reqRepo.findAll()).thenReturn(List.of(inflightWithSde, spaced, otherCorp));
        lenient().when(attRepo.findAll()).thenReturn(List.of());
        lenient().when(poolRepo.findAll()).thenReturn(List.of());
        lenient().when(poolRepo.findByCorpNm(any())).thenReturn(List.of());
        // SME 는 이 세 건 중 아무것도 소유하지 않았다 — 전부 SDE 가 들고 있다
        lenient().when(ownerRepo.findByUserIdIn(any())).thenReturn(List.of());
        // 팀원 목록의 기본값은 비어 있다. `scopeMembers` 가 자기 자신은 알아서 넣는다.
        // ⚠️ 여기 두는 이유 — `leaderSession()` 안에서 스텁하면 인자 평가가 나중이라 `twoSdes()` 를 덮어쓴다.
        lenient().when(userRepo.findByTeam("SDE3")).thenReturn(List.of());
        lenient().when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(SDE_ID, "CSD-A", true), owner(SDE_ID, "CSD-B", true), owner(SDE_ID, "CSD-C", true)));
    }

    private UserSession session() {
        UserSession s = new UserSession();
        s.setUserId(SME_ID); s.setRole("SME"); s.setLoginId("u1");
        return s;
    }

    @Test
    void 소유하지_않아도_우리_법인_건은_보인다() {
        List<RequestView> rows = svc.list(session(), "ALL");
        assertThat(rows).extracting(RequestView::reqNo).contains("CSD-A");
    }

    @Test
    void 법인명_공백_차이는_무시한다() {
        // 가입 때 손으로 적은 값과 비교하므로, 표기 하나로 조용히 0건이 되면 안 된다
        List<RequestView> rows = svc.list(session(), "ALL");
        assertThat(rows).extracting(RequestView::reqNo).contains("CSD-B");
    }

    @Test
    void 다른_법인_건은_섞이지_않는다() {
        // 인력풀 배정 기준이었다면 같은 SDE 가 들고 있는 '풀무원식품' 건까지 새어 들어온다
        List<RequestView> rows = svc.list(session(), "ALL");
        assertThat(rows).extracting(RequestView::reqNo).doesNotContain("CSD-C");
        assertThat(rows).hasSize(2);
    }

    @Test
    void SDE_가_처리중이면_할_일_종료로_보이지_않는다() {
        // 예전에는 SME 범위에 SDE 가 없어 inTodo=false → 'ITSM 할 일 종료' 배지가 잘못 붙었다
        assertThat(svc.list(session(), "ALL")).allSatisfy(r -> assertThat(r.inTodo()).isTrue());
    }

    @Test
    void 아무도_들고_있지_않으면_내려간_것으로_본다() {
        when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(SDE_ID, "CSD-A", false), owner(SDE_ID, "CSD-B", true)));
        List<RequestView> rows = svc.list(session(), "ALL");
        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-A")).allSatisfy(r -> assertThat(r.inTodo()).isFalse());
        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-B")).allSatisfy(r -> assertThat(r.inTodo()).isTrue());
    }

    // ── 이관 (`UR-260916-1`)

    private UserSession sdeSession() {
        UserSession s = new UserSession();
        s.setUserId(SDE_ID); s.setRole("SDE"); s.setLoginId("u2");
        return s;
    }

    @Test
    void 이관되면_1차_SDE_스코프에서_빠진다() {
        // 1차 SDE(SDE_ID) 의 소유 행은 비활성인데, 범위 밖의 다른 SDE 가 여전히 active 로 들고 있다
        // → "완료"가 아니라 "이관" 이므로 1차 SDE 화면에서 아예 빠져야 한다.
        long otherSdeId = 77L;
        when(userRepo.findById(SDE_ID)).thenReturn(Optional.of(sde));
        when(ownerRepo.findByUserIdIn(List.of(SDE_ID))).thenReturn(List.of(owner(SDE_ID, "CSD-A", false)));
        when(ownerRepo.findByReqNoIn(List.of("CSD-A"))).thenReturn(List.of(
                owner(SDE_ID, "CSD-A", false), owner(otherSdeId, "CSD-A", true)));

        List<RequestView> rows = svc.list(sdeSession(), "ALL");

        assertThat(rows).isEmpty();
    }

    @Test
    void 진짜_완료면_이관과_달리_이력에는_남는다() {
        // 전원(범위 안팎 통틀어) active=false — 이관이 아니라 정말 끝난 것이므로 완료 이력 보존은 그대로 지킨다.
        when(userRepo.findById(SDE_ID)).thenReturn(Optional.of(sde));
        when(ownerRepo.findByUserIdIn(List.of(SDE_ID))).thenReturn(List.of(owner(SDE_ID, "CSD-A", false)));
        when(ownerRepo.findByReqNoIn(List.of("CSD-A"))).thenReturn(List.of(owner(SDE_ID, "CSD-A", false)));

        List<RequestView> rows = svc.list(sdeSession(), "ALL");

        assertThat(rows).extracting(RequestView::reqNo).containsExactly("CSD-A");
        assertThat(rows).allSatisfy(r -> assertThat(r.inTodo()).isFalse());
    }

    @Test
    void 이관돼도_SME_시야는_영향_없다() {
        // SME 는 소유자(RequestOwner) 가 아니라 법인명(§F) 기준으로 독립적으로 본다 —
        // 1차 SDE 의 소유 행이 이관으로 스코프에서 빠져도 SME 화면은 그대로다.
        when(ownerRepo.findByReqNoIn(any())).thenReturn(List.of(
                owner(SDE_ID, "CSD-A", false), owner(77L, "CSD-A", true)));

        List<RequestView> rows = svc.list(session(), "ALL");   // SME 세션 — ownerRepo.findByUserIdIn 은 기본값 List.of()

        assertThat(rows).extracting(RequestView::reqNo).contains("CSD-A");
    }

    @Test
    void 법인명_정규화는_공백만_지운다() {
        assertThat(DashboardService.normCorp("풀무원 푸드앤컬처")).isEqualTo("풀무원푸드앤컬처");
        assertThat(DashboardService.normCorp("  풀무원푸드앤컬처 ")).isEqualTo("풀무원푸드앤컬처");
        assertThat(DashboardService.normCorp(null)).isEmpty();
        assertThat(DashboardService.normCorp("풀무원식품")).isNotEqualTo("풀무원푸드앤컬처");
    }
    // ── 담당자별 현황 (2026-09-10 · SME 대시보드에도 이 표를 띄우면서)

    private static final long LEADER_ID = 9;

    private static AppUser sdeUser(long id, String name, String perId) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole("SDE"); u.setName(name); u.setItsmPerId(perId);
        u.setLoginId("u" + id); u.setTeam("SDE3");
        return u;
    }

    /**
     * 가입한 SDE 둘 — 한 명은 우리 법인 건을 맡았고, 한 명은 한 건도 안 들었다.
     * <p>⚠️ 2026-09-10 부터 표의 행은 <b>역할이 아니라 실제 보유 관계</b>에서 나온다(`UR-260910-4` §L-3).
     * 그래서 `findByRole` 이 아니라 <b>`findByTeam`(0건 팀원을 세울 때만 쓰는 목록)</b> 과
     * `findAll`(아바타용 이름 매칭)을 채운다.
     */
    private void twoSdes() {
        AppUser mine = sdeUser(10, "임동영", "p_dy");   // req(...) 가 붙여 주는 담당자 이름
        AppUser idle = sdeUser(11, "남의팀SDE", "p_x");
        when(userRepo.findByTeam("SDE3")).thenReturn(List.of(user(LEADER_ID, "SDE_LEADER", null), mine, idle));
        when(userRepo.findAll()).thenReturn(List.of(sme, sde, mine, idle));
    }

    private UserSession leaderSession() {
        AppUser leader = user(LEADER_ID, "SDE_LEADER", null);
        leader.setTeam("SDE3");
        when(userRepo.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        when(ownerRepo.findByUserIdIn(any())).thenReturn(List.of(owner(LEADER_ID, "CSD-A", true)));
        UserSession s = new UserSession();
        s.setUserId(LEADER_ID); s.setRole("SDE_LEADER"); s.setLoginId("u9");
        return s;
    }

    @Test
    void SME_의_담당자별_현황에는_우리_법인_건이_있는_사람만_나온다() {
        // 0건인 사람은 **우리 법인과 무관한 다른 팀 SDE** 다. 그 이름이 뜨면
        // "이 사람이 우리 건을 놀리고 있다" 로 읽힌다.
        // ⚠️ 화면에서 거르는 게 아니라 응답에서 뺀다 — 개발자도구로 보이면 뺀 의미가 없다.
        twoSdes();

        List<TeamView> team = svc.stats(session()).team();

        assertThat(team).extracting(TeamView::name).containsExactly("임동영");
        assertThat(team.get(0).active()).isEqualTo(2);      // CSD-A · CSD-B (남의 법인 CSD-C 는 범위 밖)
    }

    @Test
    void 리더의_담당자별_현황에는_0건인_팀원도_남는다() {
        // 리더가 이 표에서 하는 일은 "누가 비어 있나" 다 — 0건인 사람을 빼면 배분할 근거가 사라진다.
        // SME 쪽을 고치면서 이쪽까지 조용히 바뀌지 않게 못박는다.
        twoSdes();

        List<TeamView> team = svc.stats(leaderSession()).team();

        assertThat(team).extracting(TeamView::name).contains("임동영", "남의팀SDE");
        assertThat(team).filteredOn(t -> t.name().equals("남의팀SDE"))
                .allSatisfy(t -> assertThat(t.active()).isZero());
        // 0건인 사람은 **아래로** 간다 — 위쪽은 실제로 들고 있는 사람 자리다
        assertThat(team.get(team.size() - 1).name()).isEqualTo("남의팀SDE");
    }

    @Test
    void 리더_본인도_들고_있으면_표에_나온다() {
        // 🐛 2026-09-10 이전: `findByRole("SDE")` 로 행을 만들어 **`SDE_LEADER` 인 본인이 통째로 빠졌다.**
        // 실측에서 리더 심윤범은 활성 21건으로 팀 최다 보유자였는데도 자기 표에 없었다.
        // 2026-09-09 에 인력풀 배정 대상을 SDE+리더로 넓히며 `TIER_ROLES` 까지 맞췄는데 여기만 남았던 자리다.
        twoSdes();
        inflightWithSde.setAssigneeName("사용자9");   // = 리더 본인(user(LEADER_ID, ...) 의 이름)

        List<TeamView> team = svc.stats(leaderSession()).team();

        assertThat(team).filteredOn(t -> t.name().equals("사용자9"))
                .singleElement().satisfies(t -> assertThat(t.active()).isEqualTo(1));
    }

    @Test
    void 담당자가_없는_건은_미배정_행으로_맨_위에_선다() {
        // 이 행이 없으면 미할당 건이 어느 담당자 행에도 안 들어가 **합계가 조용히 모자란다.**
        twoSdes();
        inflightWithSde.setAssigneeName(null);

        List<TeamView> team = svc.stats(leaderSession()).team();

        assertThat(team.get(0).name()).isEqualTo(DashboardService.UNASSIGNED);
        assertThat(team.get(0).active()).isEqualTo(1);
    }

    @Test
    void 담당자표의_합은_상태분포와_일치한다() {
        // ⭐ 이 작업 전체의 안전장치(`UR-260910-4` §L-3).
        // 역할 필터·모수 차이·상태 열 누락 — 어느 쪽이 다시 생겨도 여기서 빨간불이 된다.
        // 표는 **완료를 빼고** 세므로, 상태 분포에서도 DONE 만 뺀 값과 비교한다.
        twoSdes();
        spaced.setAssigneeName(null);                 // 미배정 행도 합계에 들어가야 한다
        otherCorp.setWorkStatus(BoardStatus.IN_PROGRESS);               // 범위 밖이라 어느 쪽에도 안 잡혀야 한다
        inflightWithSde.setWorkStatus("DONE");        // 완료는 합계에서 빠지고 완료 축에서만 잡힌다

        StatsResponse st = svc.stats(session());

        Map<String, Long> dist = st.dist().stream()
                .filter(d -> !"DONE".equals(d.status()))
                .collect(Collectors.toMap(DistView::status, DistView::count));
        for (Map.Entry<String, Long> e : dist.entrySet()) {
            long rows = st.team().stream().mapToLong(t -> t.byStatus().getOrDefault(e.getKey(), 0)).sum();
            assertThat(rows).as("상태 %s 의 담당자 행 합", e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(st.team().stream().mapToInt(TeamView::active).sum())
                .as("표 전체 합 = 완료를 뺀 상태 분포 합")
                .isEqualTo((int) dist.values().stream().mapToLong(Long::longValue).sum());

        // 완료도 같은 등식을 지킨다 — 합계 바깥 열이지만 전체 줄과 어긋나면 똑같이 못 믿는 표가 된다
        long doneAll = st.dist().stream().filter(d -> "DONE".equals(d.status())).mapToLong(DistView::count).sum();
        assertThat(st.team().stream().mapToInt(TeamView::done).sum())
                .as("담당자별 완료 합 = 상태 분포의 완료")
                .isEqualTo((int) doneAll);
    }

    @Test
    void 완료_건은_상태_열과_합계에서_빠진다() {
        // 표가 답하는 질문은 "지금 누가 무엇을 들고 있나" 다. 완료가 합계에 섞이면
        // 누적 완료가 많은 사람일수록 크게 보여 그 질문에 답할 수 없게 된다.
        twoSdes();
        inflightWithSde.setWorkStatus("DONE");

        StatsResponse st = svc.stats(session());

        assertThat(st.kpis().done()).isEqualTo(1);                                     // KPI 에는 남는다
        assertThat(st.team().stream().mapToInt(TeamView::active).sum()).isEqualTo(1);   // 합계는 CSD-B 만
        assertThat(st.team()).allSatisfy(t -> assertThat(t.byStatus()).doesNotContainKey("DONE"));
    }

    @Test
    void 담당자별_완료_건수는_따로_실린다() {
        // 사용자 요청(2026-09-11) — 합계 바깥의 별도 축이다.
        twoSdes();
        inflightWithSde.setWorkStatus("DONE");

        List<TeamView> team = svc.stats(session()).team();

        assertThat(team).filteredOn(t -> t.name().equals("임동영")).singleElement()
                .satisfies(t -> {
                    assertThat(t.active()).isEqualTo(1);   // CSD-B
                    assertThat(t.done()).isEqualTo(1);     // CSD-A
                });
    }

    @Test
    void 완료만_있는_사람도_행이_남는다() {
        // ⚠️ 진행 중인 건만 묶으면 **완료만 있는 사람은 행 자체가 안 생겨** 그 사람의 완료가 통째로 사라지고,
        //    전체 줄의 완료 수와도 어긋난다. 그래서 묶을 때는 완료도 함께 넣는다.
        twoSdes();
        inflightWithSde.setWorkStatus("DONE");
        spaced.setWorkStatus("DONE");

        List<TeamView> team = svc.stats(session()).team();

        assertThat(team).filteredOn(t -> t.name().equals("임동영")).singleElement()
                .satisfies(t -> {
                    assertThat(t.active()).isZero();
                    assertThat(t.done()).isEqualTo(2);
                });
    }

    // ── 새 댓글 배지·필터 (2026-09-11)

    /** 그 요청·채널에 남긴 코멘트 한 줄. `authorId` 가 나면 '새 글' 이 아니다. */
    private static RequestComment comment(String reqNo, String channel, long authorId) {
        RequestComment c = new RequestComment();
        c.setId(authorId * 100 + reqNo.hashCode() % 97);
        c.setReqNo(reqNo); c.setChannel(channel); c.setAuthorId(authorId);
        c.setAuthorRole("SDE"); c.setBody("확인 부탁합니다");
        c.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return c;
    }

    @Test
    void 새_댓글_필터는_내가_안_본_건만_남긴다() {
        // 남기기만 하고 아무도 못 찾으면 코멘트가 메신저보다 나을 게 없다(사용자 지적 2026-09-11).
        when(commentRepo.findByReqNoIn(any()))
                .thenReturn(List.of(comment("CSD-A", RequestCommentService.SME_SDE, SDE_ID)));

        List<RequestView> rows = svc.list(session(), "CMT");

        assertThat(rows).extracting(RequestView::reqNo).containsExactly("CSD-A");
        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.unreadComments()).isEqualTo(1));
    }

    @Test
    void 내가_쓴_댓글은_새_댓글_필터에_안_걸린다() {
        // 방금 내가 쓴 글이 '확인할 것' 으로 돌아오면 필터를 안 믿게 된다.
        when(commentRepo.findByReqNoIn(any()))
                .thenReturn(List.of(comment("CSD-A", RequestCommentService.SME_SDE, SME_ID)));

        assertThat(svc.list(session(), "CMT")).isEmpty();
    }

    @Test
    void 리더의_새_댓글_필터는_SME와_SDE의_대화를_잡지_않는다() {
        // ⚠️ 누출 방지의 연장이다. 내용은 못 보면서 **"저 건에 무슨 말이 오갔다" 는 사실만** 새어 나가면
        //    응답에서 뺀 의미가 없다 — 필터·배지도 내가 볼 수 있는 채널만 센다.
        twoSdes();
        when(commentRepo.findByReqNoIn(any()))
                .thenReturn(List.of(comment("CSD-A", RequestCommentService.SME_SDE, SME_ID)));

        UserSession lead = leaderSession();
        assertThat(svc.list(lead, "CMT")).isEmpty();
        assertThat(svc.list(lead, "ALL")).allSatisfy(r -> assertThat(r.unreadComments()).isZero());
        assertThat(svc.stats(lead).kpis().newComments()).isZero();
    }

    @Test
    void KPI_의_새_댓글은_코멘트_수가_아니라_건_수다() {
        // 셀렉트가 답하는 질문은 "몇 건을 확인해야 하나" 다 — 한 건에 세 줄이 달려도 확인할 건은 하나다.
        when(commentRepo.findByReqNoIn(any())).thenReturn(List.of(
                comment("CSD-A", RequestCommentService.SME_SDE, SDE_ID),
                comment("CSD-A", RequestCommentService.SME_LEAD, SDE_ID),
                comment("CSD-B", RequestCommentService.SME_SDE, SDE_ID)));

        assertThat(svc.stats(session()).kpis().newComments()).isEqualTo(2);
    }

    // ── 목록의 노트 배지 (`UR-260909-6`)

    /** 내용이 있는 공유본 하나. `publishedAt` 이 배지 판정의 한쪽 축이다. */
    private static RequestNote note(String reqNo, LocalDateTime publishedAt) {
        RequestNote n = new RequestNote();
        n.setReqNo(reqNo); n.setBodyText("분석 내용"); n.setPublishedAt(publishedAt);
        return n;
    }

    private static NoteRead read(String reqNo, long userId, LocalDateTime at) {
        NoteRead r = new NoteRead();
        r.setReqNo(reqNo); r.setUserId(userId); r.setReadAt(at);
        return r;
    }

    @Test
    void 내가_본_뒤에_공유됐으면_갱신이다() {
        LocalDateTime read = LocalDateTime.of(2026, 9, 9, 9, 0);
        when(noteRepo.findByReqNoIn(any())).thenReturn(List.of(
                note("CSD-A", read.plusHours(2)),      // 읽은 뒤 다시 공유 → 갱신
                note("CSD-B", read.minusHours(2))));   // 읽기 전에 공유된 그대로 → 그냥 노트
        when(noteReadRepo.findByUserIdAndReqNoIn(eq(SME_ID), any()))
                .thenReturn(List.of(read("CSD-A", SME_ID, read), read("CSD-B", SME_ID, read)));

        List<RequestView> rows = svc.list(session(), "ALL");

        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .allSatisfy(r -> { assertThat(r.hasNote()).isTrue(); assertThat(r.noteUpdated()).isTrue(); });
        assertThat(rows).filteredOn(r -> r.reqNo().equals("CSD-B"))
                .allSatisfy(r -> { assertThat(r.hasNote()).isTrue(); assertThat(r.noteUpdated()).isFalse(); });
    }

    @Test
    void 한번도_안_본_노트는_갱신이_아니다() {
        // 그 사람에게는 아직 '새 노트' 다. 갱신은 "내가 본 것과 다르다" 는 뜻이라 본 적이 있어야 성립한다.
        when(noteRepo.findByReqNoIn(any())).thenReturn(List.of(note("CSD-A", LocalDateTime.now())));
        when(noteReadRepo.findByUserIdAndReqNoIn(eq(SME_ID), any())).thenReturn(List.of());

        assertThat(svc.list(session(), "ALL"))
                .filteredOn(r -> r.reqNo().equals("CSD-A"))
                .allSatisfy(r -> { assertThat(r.hasNote()).isTrue(); assertThat(r.noteUpdated()).isFalse(); });
    }

    @Test
    void 초안만_있는_노트는_배지가_없다() {
        // 공유된 적이 없으므로 읽는 사람에게는 아무것도 없는 것과 같다 — 배지를 띄우면 헛걸음한다
        RequestNote draftOnly = new RequestNote();
        draftOnly.setReqNo("CSD-A");
        draftOnly.setDraftDelta("{\"ops\":[{\"insert\":\"작성 중\"}]}");
        when(noteRepo.findByReqNoIn(any())).thenReturn(List.of(draftOnly));

        assertThat(svc.list(session(), "ALL"))
                .filteredOn(r -> r.reqNo().equals("CSD-A"))
                .allSatisfy(r -> { assertThat(r.hasNote()).isFalse(); assertThat(r.noteUpdated()).isFalse(); });
    }

@Test
    void 배지는_시스템_배정에서_나온다() {
        // 2026-09-10 — 법인담당SDE 에는 차수가 없다(사용자). 차수는 시스템 줄에만 있으므로 배지도 거기서 나온다.
        when(poolRepo.findAll()).thenReturn(List.of(assignment(MY_CORP, "FNC 인사", 4, SDE_ID)));

        assertThat(svc.list(session(), "ALL")).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .allSatisfy(r -> assertThat(r.assigneeTier()).isEqualTo("4"));
    }

    @Test
    void 한_법인에서_차수가_여럿이면_이어_붙인다() {
        // ⚠️ ITSM 목록은 요청이 어느 시스템 건인지 주지 않는다. 하나를 고르면 **틀린 숫자를 조용히** 보여주게 된다.
        //    그래서 고르지 않고 있는 그대로 늘어놓는다 — 낮은 차수부터, 중복은 하나로.
        when(poolRepo.findAll()).thenReturn(List.of(
                assignment(MY_CORP, "FNC 인사", 3, SDE_ID),
                assignment(MY_CORP, "하루", 1, SDE_ID),
                assignment(MY_CORP, "MIS", 1, SDE_ID)));

        assertThat(svc.list(session(), "ALL")).filteredOn(r -> r.reqNo().equals("CSD-A"))
                .allSatisfy(r -> assertThat(r.assigneeTier()).isEqualTo("1·3"));
    }

    @Test
    void 법인담당SDE_만_있으면_배지를_붙이지_않는다() {
        // 명단에는 차수가 없다. 억지로 숫자를 만들면 **화면이 거짓말**을 한다.
        when(poolRepo.findAll()).thenReturn(List.of(assignment(MY_CORP, null, null, SDE_ID)));

        assertThat(svc.list(session(), "ALL")).allSatisfy(r -> assertThat(r.assigneeTier()).isNull());
    }
}
