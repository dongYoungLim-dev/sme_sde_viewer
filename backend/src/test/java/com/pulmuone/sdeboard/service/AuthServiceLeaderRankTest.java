package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;

import com.pulmuone.sdeboard.adapter.itsm.ItsmClient;
import com.pulmuone.sdeboard.config.SdeProperties;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SDE 리더 **정/부** 구분 (`UR-260909-2`) 의 경계를 고정한다.
 *
 * <p>여기서 지키는 것은 하나다 — <b>정/부는 권한이 아니다.</b>
 * 권한 축({@code role})에 섞이는 순간 {@code role} 문자열 비교 15곳이 조용히 어긋나고,
 * 그 사고는 컴파일 에러도 예외도 없이 "인력풀 편집 버튼이 사라지는" 모습으로만 나타난다.
 * 그래서 <b>가입 후에도 role 이 정확히 {@code SDE_LEADER} 로 남는지</b>를 첫 테스트로 둔다.
 */
class AuthServiceLeaderRankTest {

    private ItsmClient client;
    private AppUserRepository userRepo;
    private AuthService auth;

    private static final String PW = "pw";

    @BeforeEach
    void setUp() {
        client = mock(ItsmClient.class);
        userRepo = mock(AppUserRepository.class);
        ItsmRequestRepository reqRepo = mock(ItsmRequestRepository.class);
        SessionRegistry sessions = mock(SessionRegistry.class);
        SyncService sync = mock(SyncService.class);

        auth = new AuthService(client, userRepo, reqRepo, sessions, sync, new SdeProperties());

        lenient().when(client.login(anyString(), anyString())).thenReturn(
                new ItsmClient.LoginResult(true, false, "at", "rt", "p_x", "00013", "OK", null));
        lenient().when(userRepo.findByLoginId(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepo.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(sessions.create(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new UserSession());
    }

    private static SignupRequest signup(String role, String team, String rank) {
        return new SignupRequest("p_x", PW, "홍길동", null, role, null, team, rank);
    }

    private AppUser saved() {
        ArgumentCaptor<AppUser> c = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(c.capture());
        return c.getValue();
    }

    private static UserSession session(long userId) {
        UserSession s = new UserSession();
        s.setUserId(userId);
        return s;
    }

    @Test
    void 정부를_넣어도_role_은_SDE_LEADER_그대로다() {
        // 이게 무너지면 role 비교 15곳이 조용히 리더를 리더로 인식하지 못한다
        auth.signup(signup("SDE_LEADER", "SDE1", "MAIN"));
        AppUser u = saved();
        assertThat(u.getRole()).isEqualTo("SDE_LEADER");
        assertThat(u.getLeaderRank()).isEqualTo("MAIN");
    }

    @Test
    void 리더는_정부를_고르지_않으면_가입이_안_된다() {
        assertThat(auth.signup(signup("SDE_LEADER", "SDE1", null)).success()).isFalse();
        assertThat(auth.signup(signup("SDE_LEADER", "SDE1", "  ")).success()).isFalse();
        verify(userRepo, never()).save(any());
    }

    @Test
    void 모르는_값은_받지_않는다() {
        // 모르는 값을 그대로 저장하면 화면에 그대로 새어 나온다
        assertThat(auth.signup(signup("SDE_LEADER", "SDE1", "BOSS")).success()).isFalse();
        verify(userRepo, never()).save(any());
    }

    @Test
    void 소문자도_받는다() {
        auth.signup(signup("SDE_LEADER", "SDE1", "sub"));
        assertThat(saved().getLeaderRank()).isEqualTo("SUB");
    }

    @Test
    void 리더가_아니면_값이_와도_저장하지_않는다() {
        // SDE 에게 정/부가 붙으면 사이드바 배지가 거짓말을 한다
        auth.signup(signup("SDE", "SDE1", "MAIN"));
        AppUser u = saved();
        assertThat(u.getRole()).isEqualTo("SDE");
        assertThat(u.getLeaderRank()).isNull();
    }

    @Test
    void SME_는_정부가_없다() {
        SignupRequest req = new SignupRequest("p_x", PW, "홍길동", null, "SME", "풀무원푸드앤컬처", null, "MAIN");
        auth.signup(req);
        AppUser u = saved();
        assertThat(u.getRole()).isEqualTo("SME");
        assertThat(u.getLeaderRank()).isNull();
    }

    @Test
    void 마이페이지_수정은_리더만_할_수_있다() {
        for (String role : new String[]{ "SME", "SDE" }) {
            AppUser u = new AppUser();
            u.setId(9L); u.setRole(role); u.setLoginId("u9");
            when(userRepo.findById(9L)).thenReturn(Optional.of(u));
            assertThatThrownBy(() -> auth.updateProfile(session(9L), new ProfileUpdateRequest("MAIN")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("SDE 리더만");
        }
        verify(userRepo, never()).save(any());
    }

    @Test
    void 마이페이지에서_정부를_바꾼다() {
        AppUser leader = new AppUser();
        leader.setId(1L); leader.setRole("SDE_LEADER"); leader.setTeam("SDE3");
        leader.setLoginId("u1"); leader.setLeaderRank("MAIN");
        when(userRepo.findById(1L)).thenReturn(Optional.of(leader));

        auth.updateProfile(session(1L), new ProfileUpdateRequest("SUB"));

        AppUser u = saved();
        assertThat(u.getLeaderRank()).isEqualTo("SUB");
        assertThat(u.getRole()).isEqualTo("SDE_LEADER");   // 역할은 건드리지 않는다
        assertThat(u.getTeam()).isEqualTo("SDE3");         // 팀도 그대로 — 조회 범위는 여기서 못 바꾼다
    }

    @Test
    void 마이페이지도_모르는_값은_거부한다() {
        AppUser leader = new AppUser();
        leader.setId(1L); leader.setRole("SDE_LEADER"); leader.setLoginId("u1");
        when(userRepo.findById(1L)).thenReturn(Optional.of(leader));

        assertThatThrownBy(() -> auth.updateProfile(session(1L), new ProfileUpdateRequest("정")))
                .isInstanceOf(ResponseStatusException.class);
        verify(userRepo, never()).save(any());
    }
}
