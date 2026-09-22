package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.PoolDtos.*;

import com.pulmuone.sdeboard.config.SdeProperties;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.SdeAssignment;
import com.pulmuone.sdeboard.domain.TeamCorp;
import com.pulmuone.sdeboard.domain.TeamSystem;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.SdeAssignmentRepository;
import com.pulmuone.sdeboard.repo.TeamCorpRepository;
import com.pulmuone.sdeboard.repo.TeamSystemRepository;
import com.pulmuone.sdeboard.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SDE 인력풀의 **권한과 무결성 규칙**을 고정한다.
 *
 * <p>이 표는 ITSM 어디에도 없는 데이터라 <b>틀려도 대조할 원본이 없다.</b>
 * 그래서 "누가 고칠 수 있나"와 "법인당 차수 1명"을 코드가 아니라 테스트로 못박는다.
 */
class SdePoolServiceTest {

    private SdeAssignmentRepository poolRepo;
    private TeamCorpRepository corpRepo;
    private TeamSystemRepository systemRepo;
    private AppUserRepository userRepo;
    private ItsmRequestRepository reqRepo;
    private SdePoolService svc;

    private static final long LEADER_ID = 1, SDE_ID = 2, OTHER_SDE_ID = 3, SME_ID = 4;
    private static final String CORP = "풀무원푸드앤컬처";

    private static AppUser user(long id, String role, String team, String corpNm) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setTeam(team); u.setCorpNm(corpNm);
        u.setLoginId("u" + id); u.setName("사용자" + id); u.setLinkStatus("LINKED");
        return u;
    }

    /** `systemNm == null` = 법인담당SDE(차수 없음). 차수는 시스템 줄에만 있다 (2026-09-10). */
    private static SdeAssignment assignment(String corpNm, String systemNm, Integer tier, long userId) {
        SdeAssignment a = new SdeAssignment();
        a.setCorpNm(corpNm); a.setSystemNm(systemNm); a.setTier(tier); a.setUserId(userId);
        return a;
    }

    private static UserSession session(long userId, String role) {
        UserSession s = new UserSession();
        s.setUserId(userId); s.setRole(role);
        return s;
    }

    private final AppUser leader = user(LEADER_ID, "SDE_LEADER", "SDE1", null);
    private final AppUser sde = user(SDE_ID, "SDE", "SDE1", null);
    private final AppUser otherTeamSde = user(OTHER_SDE_ID, "SDE", "SDE2", null);
    private final AppUser sme = user(SME_ID, "SME", null, CORP);

    @BeforeEach
    void setUp() {
        poolRepo = mock(SdeAssignmentRepository.class);
        corpRepo = mock(TeamCorpRepository.class);
        systemRepo = mock(TeamSystemRepository.class);
        userRepo = mock(AppUserRepository.class);
        reqRepo = mock(ItsmRequestRepository.class);
        svc = new SdePoolService(poolRepo, corpRepo, systemRepo, userRepo, reqRepo, new SdeProperties());

        lenient().when(userRepo.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        lenient().when(userRepo.findById(SDE_ID)).thenReturn(Optional.of(sde));
        lenient().when(userRepo.findById(OTHER_SDE_ID)).thenReturn(Optional.of(otherTeamSde));
        lenient().when(userRepo.findById(SME_ID)).thenReturn(Optional.of(sme));
        lenient().when(userRepo.findAll()).thenReturn(List.of(leader, sde, otherTeamSde, sme));
        lenient().when(userRepo.findByRoleAndTeam("SDE", "SDE1")).thenReturn(List.of(sde));
        // 2026-09-09 — 배정 대상은 SDE 뿐 아니라 **리더 자신**도 포함한다
        lenient().when(userRepo.findByRoleInAndTeam(anyList(), eq("SDE1"))).thenReturn(List.of(leader, sde));
        lenient().when(userRepo.findByRoleInAndTeam(anyList(), eq("SDE2"))).thenReturn(List.of(otherTeamSde));
        lenient().when(reqRepo.findAll()).thenReturn(List.of());
        lenient().when(poolRepo.findByUserId(anyLong())).thenReturn(List.of());
        lenient().when(poolRepo.findByUserIdIn(any())).thenReturn(List.of());
        lenient().when(poolRepo.findByCorpNm(anyString())).thenReturn(List.of());
        lenient().when(poolRepo.findByCorpNmIn(any())).thenReturn(List.of());
        // 시스템 줄 — 기본은 없다(= 예전처럼 법인 줄만 서는 상태)
        lenient().when(systemRepo.findByCorpNm(anyString())).thenReturn(List.of());
        lenient().when(systemRepo.findByCorpNmIn(any())).thenReturn(List.of());
        lenient().when(systemRepo.findByCorpNmAndSystemNm(anyString(), anyString())).thenReturn(Optional.empty());
        // 차수 배정은 **시스템 줄에만** 붙으므로, 배정 테스트는 시스템 하나가 서 있는 상태에서 출발한다
        lenient().when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(teamSystem(CORP, SYS)));
        lenient().when(systemRepo.save(any(TeamSystem.class))).thenAnswer(i -> i.getArgument(0));
        // 담당 법인 표 — 기본은 비어 있다(2026-09-09: 관측 법인을 자동으로 세우지 않는다)
        lenient().when(corpRepo.findByTeam(anyString())).thenReturn(List.of());
        lenient().when(corpRepo.findByCorpNm(anyString())).thenReturn(Optional.empty());
        lenient().when(corpRepo.findAll()).thenReturn(List.of());
        lenient().when(corpRepo.save(any(TeamCorp.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 배정은_리더만_고칠_수_있다() {
        assertThatThrownBy(() -> svc.assign(session(SME_ID, "SME"), new PoolAssignRequest(CORP, SYS, 1, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE 리더만");
        assertThatThrownBy(() -> svc.assign(session(SDE_ID, "SDE"), new PoolAssignRequest(CORP, SYS, 1, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class);
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 다른_팀_SDE_는_배정할_수_없다() {
        assertThatThrownBy(() -> svc.assign(session(LEADER_ID, "SDE_LEADER"),
                new PoolAssignRequest(CORP, SYS, 1, OTHER_SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 팀");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void SME_는_배정_대상이_아니다() {
        // 요청을 내는 쪽이라 담당 차수가 없다. 리더가 대상에 들어와도 이 경계는 그대로다.
        assertThatThrownBy(() -> svc.assign(session(LEADER_ID, "SDE_LEADER"),
                new PoolAssignRequest(CORP, SYS, 1, SME_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE 또는 SDE 리더");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 리더는_자기_자신을_배정할_수_있다() {
        // 2026-09-09 `UR-260909-1` — 리더가 실무를 함께 맡는 팀이 있다
        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 1, LEADER_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(LEADER_ID);
    }

    @Test
    void 리더_자신의_배정도_표에_보인다() {
        // ⚠️ 조회 범위가 '팀 SDE' 기준이면 리더는 자기 배정을 자기가 못 본다 — 그 구멍을 막는다
        SdeAssignment mine = assignment(CORP, null, null, LEADER_ID);
        mine.setId(7L); mine.setUpdatedAt(LocalDateTime.now());
        when(poolRepo.findByUserIdIn(argThat(ids -> ids.contains(LEADER_ID)))).thenReturn(List.of(mine));

        var pool = svc.pool(session(LEADER_ID, "SDE_LEADER"));

        assertThat(pool.corps()).anySatisfy(c ->
                assertThat(c.leads()).extracting("userId").contains(LEADER_ID));
    }

    @Test
    void 배정_후보에_리더가_들어간다() {
        var pool = svc.pool(session(LEADER_ID, "SDE_LEADER"));
        assertThat(pool.members()).extracting("userId").contains(LEADER_ID, SDE_ID);
        // 목록에서 구분할 수 있어야 한다 — 리더도 이름만 뜨면 누가 리더인지 모른다
        assertThat(pool.members()).filteredOn("userId", LEADER_ID)
                .allSatisfy(m -> assertThat(m.role()).isEqualTo("SDE_LEADER"));
    }

    @Test
    void 다른_팀_리더는_여전히_배정할_수_없다() {
        AppUser otherLeader = user(9L, "SDE_LEADER", "SDE2", null);
        when(userRepo.findById(9L)).thenReturn(Optional.of(otherLeader));

        assertThatThrownBy(() -> svc.assign(session(LEADER_ID, "SDE_LEADER"),
                new PoolAssignRequest(CORP, SYS, 1, 9L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 팀");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 차수는_1부터_설정값까지만_받는다() {
        UserSession s = session(LEADER_ID, "SDE_LEADER");
        // 기본 5차 — 2026-09-09 `UR-260909-1` 로 4→5. 차수의 *의미*는 그대로다("5차 담당자가 추가된 것")
        for (Integer bad : new Integer[]{ null, 0, 6, -1 })
            assertThatThrownBy(() -> svc.assign(s, new PoolAssignRequest(CORP, SYS, bad, SDE_ID)))
                    .isInstanceOf(ResponseStatusException.class);
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 오차_담당자를_배정할_수_있다() {
        // 2026-09-09 `UR-260909-1` — 4차까지였던 표에 5차가 늘었다. 설정값만 올리면 되는지 확인한다
        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 5, SDE_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getTier()).isEqualTo(5);
    }

    @Test
    void 같은_칸에_다시_배정하면_새_행이_아니라_덮어쓴다() {
        // 이게 무너지면 "1차 담당자"가 둘이 되고, 어느 쪽이 맞는지 대조할 원본이 없다
        SdeAssignment existing = assignment(CORP, SYS, 1, OTHER_SDE_ID);
        existing.setId(99L); existing.setUpdatedAt(LocalDateTime.now());
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(existing));

        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 1, SDE_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(99L);          // 새 행이 아니다
        assertThat(saved.getValue().getUserId()).isEqualTo(SDE_ID);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(LEADER_ID);   // 누가 고쳤는지 남는다
    }

    @Test
    void 칸_비우기는_행을_지운다() {
        SdeAssignment existing = assignment(CORP, SYS, 2, SDE_ID);
        existing.setId(99L);
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(existing));

        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 2, null));

        verify(poolRepo).delete(existing);
        verify(poolRepo, never()).save(any());
    }

    // ── 표에 세울 줄 (team_corp · team_system) — 2026-09-09 `UR-260909-1` · 2026-09-10 `UR-260910-1`

    private static TeamCorp teamCorp(String team, String corp) {
        TeamCorp tc = new TeamCorp();
        tc.setId(50L); tc.setTeam(team); tc.setCorpNm(corp); tc.setAddedAt(LocalDateTime.now());
        return tc;
    }

    @Test
    void 관측된_법인을_자동으로_세우지_않는다() {
        // 사용자 정정 2026-09-09 — 관련 없는 법인이 빈 줄로 자리만 차지하던 문제
        ItsmRequest r = new ItsmRequest();
        r.setReqNo("CSD1"); r.setReqCompNm("관련없는법인");
        when(reqRepo.findAll()).thenReturn(List.of(r));

        assertThat(svc.pool(session(LEADER_ID, "SDE_LEADER")).corps()).isEmpty();
    }

    @Test
    void 우리_팀이_담당하는_법인만_표에_선다() {
        when(corpRepo.findByTeam("SDE1")).thenReturn(List.of(teamCorp("SDE1", CORP)));

        assertThat(svc.pool(session(LEADER_ID, "SDE_LEADER")).corps())
                .extracting("corpNm").containsExactly(CORP);
    }

    @Test
    void 법인_추가는_리더만_할_수_있다() {
        for (long id : new long[]{ SME_ID, SDE_ID })
            assertThatThrownBy(() -> svc.addRow(session(id, ""), new PoolRowRequest(CORP, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("SDE 리더만");
        verify(corpRepo, never()).save(any());
    }

    @Test
    void 다른_팀이_담당하는_법인은_추가할_수_없다() {
        // "한 법인 = 한 팀"(사용자 2026-09-09). 조용히 공유시키면 상대 팀 칸이 내 화면에 뜨는데 고칠 수는 없다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE2", CORP)));

        assertThatThrownBy(() -> svc.addRow(session(LEADER_ID, "SDE_LEADER"), new PoolRowRequest(CORP, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE2");
        verify(corpRepo, never()).save(any());
    }

    @Test
    void 법인을_추가하면_우리_팀_표에_들어간다() {
        svc.addRow(session(LEADER_ID, "SDE_LEADER"), new PoolRowRequest("  " + CORP + "  ", null));

        ArgumentCaptor<TeamCorp> saved = ArgumentCaptor.forClass(TeamCorp.class);
        verify(corpRepo).save(saved.capture());
        assertThat(saved.getValue().getCorpNm()).isEqualTo(CORP);      // 공백은 다듬는다
        assertThat(saved.getValue().getTeam()).isEqualTo("SDE1");
        assertThat(saved.getValue().getAddedBy()).isEqualTo(LEADER_ID);
    }

    @Test
    void 담당_명단이_남은_법인은_내릴_수_없다() {
        // 지우면 복구할 곳이 없다 — ITSM 에 없는 데이터다. 무엇이 사라지는지 사람이 보고 지우게 한다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(assignment(CORP, null, null, SDE_ID)));

        assertThatThrownBy(() -> svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("먼저 명단을 비운");
        verify(corpRepo, never()).delete(any());
    }

    @Test
    void 빈_법인은_내릴_수_있다() {
        TeamCorp tc = teamCorp("SDE1", CORP);
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(tc));
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of());

        svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, null);

        verify(corpRepo).delete(tc);
    }

    @Test
    void 다른_팀의_법인은_내릴_수_없다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE2", CORP)));

        assertThatThrownBy(() -> svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 팀");
        verify(corpRepo, never()).delete(any());
    }

    @Test
    void 추가_후보는_설정_목록과_관측값을_합치고_이미_있는_줄은_뺀다() {
        ItsmRequest r = new ItsmRequest();
        r.setReqNo("CSD1"); r.setReqCompNm("관측만된법인");
        when(reqRepo.findAll()).thenReturn(List.of(r));
        when(corpRepo.findByTeam("SDE1")).thenReturn(List.of(teamCorp("SDE1", CORP)));

        var options = svc.pool(session(LEADER_ID, "SDE_LEADER")).corpOptions();

        assertThat(options).extracting("corpNm")
                .contains("관측만된법인", "올가홀푸드")   // 관측값 + yml 후보
                .contains(CORP);                        // 이미 표에 있어도 남는다 — 그 아래 시스템을 올려야 한다
        // 다만 화면이 구분할 수 있어야 한다
        assertThat(options).filteredOn("corpNm", CORP).allSatisfy(o -> assertThat(o.inTable()).isTrue());
        // ⚠️ 내 팀이 담당하는 법인에 `takenByTeam` 이 붙으면 화면이 **자기 법인을 잠근다**
        assertThat(options).filteredOn("corpNm", CORP).allSatisfy(o -> assertThat(o.takenByTeam()).isNull());
        // 관측 여부를 화면에 알려야 한다 — 이름이 ITSM 표기와 다르면 조용히 안 맞는다
        assertThat(options).filteredOn("corpNm", "관측만된법인").allSatisfy(o -> assertThat(o.observed()).isTrue());
        assertThat(options).filteredOn("corpNm", "올가홀푸드").allSatisfy(o -> assertThat(o.observed()).isFalse());
    }

    @Test
    void 역할별로_보이는_범위가_다르다() {
        // 리더 = 내 팀의 배정 (법인 무관 — "팀원이 어디를 맡고 있나").
        // ⚠️ 2026-09-09 부터 **리더 자신이 포함된다** — 빠지면 자기 배정을 자기가 못 본다
        svc.pool(session(LEADER_ID, "SDE_LEADER"));
        verify(poolRepo).findByUserIdIn(List.of(LEADER_ID, SDE_ID));
        verify(poolRepo, never()).findByCorpNm(anyString());

        // SME = 내 법인의 차수별 담당자 (팀 무관)
        clearInvocations(poolRepo);
        svc.pool(session(SME_ID, "SME"));
        verify(poolRepo).findByCorpNm(CORP);
        verify(poolRepo, never()).findByUserIdIn(any());

        // SDE = **내가 배정된 법인**의 차수 전부 (2026-09-09 `UR-260909-5`)
        // ⚠️ 배정이 없으면 IN 조회를 던지지 않는다 — 빈 목록으로 `in ()` 을 만들지 않기 위해서다
        clearInvocations(poolRepo);
        svc.pool(session(SDE_ID, "SDE"));
        verify(poolRepo).findByUserId(SDE_ID);
        verify(poolRepo, never()).findByCorpNmIn(any());
        verify(poolRepo, never()).findByUserIdIn(any());
    }

    @Test
    void SDE_는_내가_배정된_법인의_다른_차수도_본다() {
        // 나는 그 시스템 2차 담당이고, 같은 시스템 1차는 다른 팀원이다.
        // 예전에는 내 행만 읽어서 1차가 `미배정` 으로 보였다 — 실제로는 사람이 있는데도.
        when(poolRepo.findByUserId(SDE_ID)).thenReturn(List.of(assignment(CORP, SYS, 2, SDE_ID)));
        when(poolRepo.findByCorpNmIn(argThat(c -> c.contains(CORP))))
                .thenReturn(List.of(assignment(CORP, SYS, 1, LEADER_ID), assignment(CORP, SYS, 2, SDE_ID)));

        var corps = svc.pool(session(SDE_ID, "SDE")).corps();

        assertThat(corps).hasSize(1);
        assertThat(corps.get(0).corpNm()).isEqualTo(CORP);
        assertThat(corps.get(0).systems()).hasSize(1);
        var sys = corps.get(0).systems().get(0);
        assertThat(sys.systemNm()).isEqualTo(SYS);
        assertThat(sys.filled()).isEqualTo(2);
        assertThat(sys.tiers()).filteredOn(c -> c.tier() == 1)
                .allSatisfy(c -> assertThat(c.userId()).isEqualTo(LEADER_ID));
        // 편집 권한이 넓어진 것은 아니다 — 보이기만 한다
        assertThat(svc.pool(session(SDE_ID, "SDE")).editable()).isFalse();
    }

    @Test
    void 배정이_없는_SDE_는_아무_법인도_보지_않는다() {
        // 넓힌 범위는 **내 배정에서 나온 법인**뿐이다. 배정이 없으면 볼 근거도 없다.
        assertThat(svc.pool(session(SDE_ID, "SDE")).corps()).isEmpty();
        verify(poolRepo, never()).findByCorpNmIn(any());
    }

    @Test
    void 내가_어느_칸인지_응답이_알려준다() {
        // 같은 법인의 다른 차수가 함께 차므로 화면이 "나" 를 표시할 수 있어야 한다
        assertThat(svc.pool(session(SDE_ID, "SDE")).meUserId()).isEqualTo(SDE_ID);
        assertThat(svc.pool(session(LEADER_ID, "SDE_LEADER")).meUserId()).isEqualTo(LEADER_ID);
    }

    @Test
    void 편집_권한은_리더에게만_열린다() {
        assertThat(svc.pool(session(LEADER_ID, "SDE_LEADER")).editable()).isTrue();
        assertThat(svc.pool(session(SME_ID, "SME")).editable()).isFalse();
        assertThat(svc.pool(session(SDE_ID, "SDE")).editable()).isFalse();
    }
    // ── 시스템 줄 (team_system) — 2026-09-10 `UR-260910-1`
    //    "현재 법인의 차수별 담당자가 시스템별 차수별 담당자가 되어야 한다"(사용자).
    //    ⚠️ 기존 배정은 **옮기지 않았다** — system_nm = null 이 그대로 법인담당SDE 줄이다.

    private static final String SYS = "FNC 인사";

    private static TeamSystem teamSystem(String corp, String system) {
        TeamSystem ts = new TeamSystem();
        ts.setId(60L); ts.setCorpNm(corp); ts.setSystemNm(system); ts.setAddedAt(LocalDateTime.now());
        return ts;
    }

    @Test
    void 법인_블록_안에_시스템_줄이_선다() {
        when(corpRepo.findByTeam("SDE1")).thenReturn(List.of(teamCorp("SDE1", CORP)));
        when(systemRepo.findByCorpNmIn(any())).thenReturn(List.of(teamSystem(CORP, SYS)));

        var corps = svc.pool(session(LEADER_ID, "SDE_LEADER")).corps();

        assertThat(corps).extracting("corpNm").containsExactly(CORP);
        assertThat(corps.get(0).systems()).extracting("systemNm").containsExactly(SYS);
        // 담당자가 아직 없어도 시스템 줄은 서 있어야 한다("추가해 뒀다"는 사실이 남아야 한다)
        assertThat(corps.get(0).systems().get(0).filled()).isZero();
    }

    @Test
    void 시스템만_고르면_법인_줄도_함께_선다() {
        // 두 번 나눠 누르게 할 이유가 없다 — 법인이 표에 없어도 한 번에 세운다
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.empty());   // 아직 없는 줄이다
        svc.addRow(session(LEADER_ID, "SDE_LEADER"), new PoolRowRequest(CORP, "  " + SYS + "  "));

        verify(corpRepo).save(argThat(tc -> CORP.equals(tc.getCorpNm()) && "SDE1".equals(tc.getTeam())));
        ArgumentCaptor<TeamSystem> saved = ArgumentCaptor.forClass(TeamSystem.class);
        verify(systemRepo).save(saved.capture());
        assertThat(saved.getValue().getSystemNm()).isEqualTo(SYS);        // 공백은 다듬는다
        assertThat(saved.getValue().getAddedBy()).isEqualTo(LEADER_ID);
    }

    @Test
    void 이미_있는_시스템_줄은_다시_만들지_않는다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(teamSystem(CORP, SYS)));

        svc.addRow(session(LEADER_ID, "SDE_LEADER"), new PoolRowRequest(CORP, SYS));

        verify(systemRepo, never()).save(any());
        verify(corpRepo, never()).save(any());
    }

    @Test
    void 다른_팀_법인_아래에는_시스템을_올릴_수_없다() {
        // "한 법인 = 한 팀" 은 시스템 줄에도 그대로다 — 아니면 남의 표에 내 줄이 선다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE2", CORP)));

        assertThatThrownBy(() -> svc.addRow(session(LEADER_ID, "SDE_LEADER"), new PoolRowRequest(CORP, SYS)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE2");
        verify(systemRepo, never()).save(any());
    }

    @Test
    void 표에_없는_시스템에는_배정할_수_없다() {
        // 줄이 없으면 칸도 없다 — 어느 화면에도 안 보이는 배정이 생긴다
        assertThatThrownBy(() -> svc.assign(session(LEADER_ID, "SDE_LEADER"),
                new PoolAssignRequest(CORP, "등록안된시스템", 1, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("표에 없는 시스템");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 같은_차수라도_시스템이_다르면_다른_칸이다() {
        // 이게 무너지면 시스템 담당자를 넣는 순간 **법인담당SDE 명단이 사라진다**
        SdeAssignment lead = assignment(CORP, null, null, OTHER_SDE_ID);
        lead.setId(99L);
        SdeAssignment common = lead;
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(common));
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(teamSystem(CORP, SYS)));

        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 1, SDE_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();               // 덮어쓰지 않는다
        assertThat(saved.getValue().getSystemNm()).isEqualTo(SYS);
        verify(poolRepo, never()).delete(any());
    }

    @Test
    void 같은_시스템_같은_차수는_덮어쓴다() {
        SdeAssignment existing = assignment(CORP, SYS, 2, OTHER_SDE_ID);
        existing.setId(88L);
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(existing));
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(teamSystem(CORP, SYS)));

        svc.assign(session(LEADER_ID, "SDE_LEADER"), new PoolAssignRequest(CORP, SYS, 2, SDE_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(88L);
        assertThat(saved.getValue().getUserId()).isEqualTo(SDE_ID);
    }

    @Test
    void 배정이_남은_시스템_줄은_내릴_수_없다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(teamSystem(CORP, SYS)));
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(assignment(CORP, SYS, 1, SDE_ID)));

        assertThatThrownBy(() -> svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, SYS))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("먼저 칸을 비운");
        verify(systemRepo, never()).delete(any());
    }

    @Test
    void 시스템_줄만_내리면_법인_줄은_남는다() {
        TeamSystem ts = teamSystem(CORP, SYS);
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(systemRepo.findByCorpNmAndSystemNm(CORP, SYS)).thenReturn(Optional.of(ts));

        svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, SYS);

        verify(systemRepo).delete(ts);
        verify(corpRepo, never()).delete(any());
    }

    @Test
    void 시스템_줄이_남은_법인은_통째로_내릴_수_없다() {
        // 한 번에 (시스템 수 x 차수) 칸이 사라진다 — ITSM 에 없는 데이터라 지우면 복구할 곳이 없다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(systemRepo.findByCorpNm(CORP)).thenReturn(List.of(teamSystem(CORP, SYS)));

        assertThatThrownBy(() -> svc.removeRow(session(LEADER_ID, "SDE_LEADER"), CORP, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("먼저 시스템 줄을 내려");
        verify(corpRepo, never()).delete(any());
    }

    @Test
    void 줄이_사라져도_배정이_남았으면_표에_보인다() {
        // team_system 을 지우는 길과 배정을 지우는 길이 따로라 한쪽만 남는 순간이 생길 수 있다.
        // 그때 줄이 안 서면 **아무도 지울 수 없는 유령 배정**이 된다.
        when(corpRepo.findByTeam("SDE1")).thenReturn(List.of(teamCorp("SDE1", CORP)));
        when(poolRepo.findByUserIdIn(any())).thenReturn(List.of(assignment(CORP, SYS, 3, SDE_ID)));
        when(systemRepo.findByCorpNmIn(any())).thenReturn(List.of());     // 줄은 이미 없다

        var corps = svc.pool(session(LEADER_ID, "SDE_LEADER")).corps();

        assertThat(corps.get(0).systems()).extracting("systemNm").containsExactly(SYS);
    }

    @Test
    void 시스템_후보는_리더에게만_간다() {
        // 읽기만 하는 SME·SDE 에게는 고를 것이 없다 — 응답에 실어 보낼 이유도 없다
        assertThat(svc.pool(session(LEADER_ID, "SDE_LEADER")).systemOptions()).contains("FNC 인사", "PQMS");
        assertThat(svc.pool(session(SME_ID, "SME")).systemOptions()).isEmpty();
        assertThat(svc.pool(session(SDE_ID, "SDE")).systemOptions()).isEmpty();
    }

    // ── 법인담당SDE 명단 (차수 없음) — 2026-09-10, 사용자: "법인의 담당자는 차수가 없습니다"

    @Test
    void 법인담당SDE_는_차수_없이_등록된다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));

        svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SDE_ID));

        ArgumentCaptor<SdeAssignment> saved = ArgumentCaptor.forClass(SdeAssignment.class);
        verify(poolRepo).save(saved.capture());
        assertThat(saved.getValue().getTier()).isNull();          // ★ 순번이 아니라 명단이다
        assertThat(saved.getValue().getSystemNm()).isNull();
        assertThat(saved.getValue().getUserId()).isEqualTo(SDE_ID);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(LEADER_ID);
    }

    @Test
    void 법인에는_차수를_배정할_수_없다() {
        // 차수는 시스템 줄에만 있다. 법인 경로로 들어오면 어느 시스템 것인지 아무도 모르는 배정이 된다
        assertThatThrownBy(() -> svc.assign(session(LEADER_ID, "SDE_LEADER"),
                new PoolAssignRequest(CORP, null, 1, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("법인담당SDE 는 차수가 없습니다");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 법인담당SDE_는_인원_제한이_없다() {
        // 사용자 2026-09-10: "5명만 등록된다는 보장도 없다" — tiers(5)를 넘겨도 막지 않는다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(
                assignment(CORP, null, null, 101L), assignment(CORP, null, null, 102L),
                assignment(CORP, null, null, 103L), assignment(CORP, null, null, 104L),
                assignment(CORP, null, null, 105L)));

        svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SDE_ID));

        verify(poolRepo).save(any());
    }

    @Test
    void 같은_사람을_명단에_두_번_넣지_않는다() {
        // 두 번 들어가면 명단이 조용히 늘어나고, 뺄 때 어느 쪽이 빠졌는지 알 수 없다
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(assignment(CORP, null, null, SDE_ID)));

        assertThatThrownBy(() -> svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 이 법인의 담당");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 명단도_리더만_그리고_자기_팀만_바꾼다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));

        assertThatThrownBy(() -> svc.addLead(session(SDE_ID, "SDE"), new PoolLeadRequest(CORP, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE 리더만");
        assertThatThrownBy(() -> svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, OTHER_SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 팀");
        assertThatThrownBy(() -> svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SME_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SDE 또는 SDE 리더");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 다른_팀의_법인_명단은_바꿀_수_없다() {
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE2", CORP)));

        assertThatThrownBy(() -> svc.addLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SDE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 팀");
        verify(poolRepo, never()).save(any());
    }

    @Test
    void 명단에서_빼면_그_행만_지운다() {
        SdeAssignment mine = assignment(CORP, null, null, SDE_ID);
        mine.setId(41L);
        when(corpRepo.findByCorpNm(CORP)).thenReturn(Optional.of(teamCorp("SDE1", CORP)));
        // 같은 법인의 시스템 배정은 건드리면 안 된다 — 명단에서 뺀 것이지 담당을 지운 게 아니다
        when(poolRepo.findByCorpNm(CORP)).thenReturn(List.of(mine, assignment(CORP, SYS, 1, SDE_ID)));

        svc.removeLead(session(LEADER_ID, "SDE_LEADER"), new PoolLeadRequest(CORP, SDE_ID));

        verify(poolRepo).delete(mine);
    }

    @Test
    void 명단은_등록_순서대로_보인다() {
        // 순번이 아니라고 했으니 이름순으로 섞으면 방금 넣은 사람을 다시 찾아야 한다
        SdeAssignment first = assignment(CORP, null, null, SDE_ID);
        first.setId(1L);
        SdeAssignment second = assignment(CORP, null, null, LEADER_ID);
        second.setId(2L);
        when(corpRepo.findByTeam("SDE1")).thenReturn(List.of(teamCorp("SDE1", CORP)));
        when(poolRepo.findByUserIdIn(any())).thenReturn(List.of(second, first));

        var leads = svc.pool(session(LEADER_ID, "SDE_LEADER")).corps().get(0).leads();

        assertThat(leads).extracting("userId").containsExactly(SDE_ID, LEADER_ID);
    }
}
