package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;

import com.pulmuone.sdeboard.adapter.itsm.ItsmClient;
import com.pulmuone.sdeboard.config.SdeProperties;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.security.SessionRegistry;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 회원가입 / 로그인.
 *
 * 로그인 = **ITSM 계정 인증**이다. 우리 시스템은 별도 비밀번호를 두지 않고,
 * ITSM 로그인 성공을 인증으로 삼는다. ITSM 비밀번호는 저장하지 않는다(세션 메모리 전용).
 *
 * ⚠️ 인증 실패는 **재시도하지 않는다.** 반복 실패는 사용자의 ITSM 계정을 잠글 수 있다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final ItsmClient client;
    private final AppUserRepository userRepo;
    private final ItsmRequestRepository reqRepo;
    private final SessionRegistry sessions;
    private final SyncService syncService;
    private final SdeProperties sdeProps;

    /** 가입 가능한 역할. SDE 는 2026-09-08 에 열렸다 — §0 "SDE 행동 변화 0" 원칙을 완화한 결과. */
    private static final Set<String> ROLES = Set.of("SME", "SDE_LEADER", "SDE");

    /**
     * SDE 리더의 정/부. <b>권한 차이는 없다</b>(사용자 확인 2026-09-09) — 그래서 {@code role} 이 아니라
     * 별도 컬럼이고, 여기서 값을 좁히는 것으로 끝난다.
     */
    private static final Set<String> LEADER_RANKS = Set.of("MAIN", "SUB");

    @Transactional
    public LoginResponse signup(SignupRequest req) {
        String loginId = trim(req.itsmUsername());
        if (loginId == null || req.itsmPassword() == null || req.itsmPassword().isBlank())
            return fail("ITSM 계정과 비밀번호를 입력하세요.");
        if (trim(req.name()) == null) return fail("이름을 입력하세요.");
        String role = ROLES.contains(req.role()) ? req.role() : "SME";
        if ("SME".equals(role) && trim(req.corpNm()) == null) return fail("소속 법인을 입력하세요.");
        // 리더와 SDE 는 둘 다 팀 소속이다 — 리더의 조회 범위가 '같은 팀'이라 팀명이 맞아야 이어진다
        if (!"SME".equals(role) && trim(req.team()) == null) return fail("소속 팀을 선택하세요.");
        // 정/부는 리더에게만 묻는다. 리더가 아닌데 값이 오면 저장하지 않고 버린다(아래 leaderRank).
        if ("SDE_LEADER".equals(role) && leaderRank(req.leaderRank()) == null)
            return fail("정/부를 선택하세요.");
        if (userRepo.findByLoginId(loginId).isPresent())
            return fail("이미 가입된 ITSM 계정입니다. 로그인하세요.");

        // 가입 시점에 ITSM 로그인으로 자격증명을 검증한다 (잘못된 계정이 저장되는 것을 원천 차단)
        ItsmClient.LoginResult r = client.login(loginId, req.itsmPassword());
        if (!r.success()) return fail(authMessage(r));

        AppUser u = new AppUser();
        u.setLoginId(loginId);
        u.setItsmPerId(r.perId() != null ? r.perId() : loginId);
        u.setItsmCompCd(r.compCd());
        u.setName(trim(req.name()));
        u.setEmail(trim(req.email()));
        u.setRole(role);
        if ("SME".equals(role)) {
            u.setCorpCd(r.compCd());                 // 법인코드는 ITSM 토큰에서 자동 취득
            u.setCorpNm(trim(req.corpNm()));
        } else {
            u.setTeam(trim(req.team()));
            // ⚠️ 리더가 아니면 값이 와도 넣지 않는다 — SDE 에게 정/부가 붙으면 화면이 거짓말을 한다
            if ("SDE_LEADER".equals(role)) u.setLeaderRank(leaderRank(req.leaderRank()));
        }
        // SDE 는 담당 법인·차수를 여기서 받지 않는다 — **리더가 인력풀 화면에서 배정**한다
        // (사용자 결정 2026-09-08: 유지 주체 = 리더. 본인 신고로 두면 누락·중복을 아무도 못 막는다)
        u.setLinkStatus("LINKED");
        userRepo.save(u);
        log.info("회원가입 완료 — loginId={} role={} compCd={}", loginId, role, r.compCd());

        return startSession(u, r, req.itsmPassword());
    }

    @Transactional
    public LoginResponse login(LoginRequest req) {
        String loginId = trim(req.itsmUsername());
        if (loginId == null || req.itsmPassword() == null || req.itsmPassword().isBlank())
            return fail("ITSM 계정과 비밀번호를 입력하세요.");
        AppUser u = userRepo.findByLoginId(loginId).orElse(null);
        if (u == null) return new LoginResponse(false, null, null, null, null, "가입되지 않은 계정입니다. 회원가입을 먼저 하세요.", true);

        ItsmClient.LoginResult r = client.login(loginId, req.itsmPassword());
        if (!r.success()) {
            if (r.authFailed()) {
                u.setLinkStatus("AUTH_FAILED");
                userRepo.save(u);
            }
            return fail(authMessage(r));
        }
        u.setLinkStatus("LINKED");
        if (r.compCd() != null) u.setItsmCompCd(r.compCd());
        userRepo.save(u);
        return startSession(u, r, req.itsmPassword());
    }

    private LoginResponse startSession(AppUser u, ItsmClient.LoginResult r, String credential) {
        UserSession s = sessions.create(u.getId(), u.getLoginId(), u.getName(), u.getRole(),
                r.accessToken(), r.refreshToken(), credential.toCharArray());
        // 로그인 직후 1회 동기화 — 화면에 바로 내 할 일이 보이도록
        try {
            syncService.syncSession(s);
        } catch (Exception e) {
            log.warn("로그인 직후 동기화 실패: {}", e.getMessage());
        }
        return new LoginResponse(true, s.getSessionId(), u.getLoginId(), u.getName(), u.getRole(), "OK", false);
    }

    public void logout(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 마이페이지 저장 — **정/부만 고칠 수 있다.**
     *
     * <p>⚠️ 팀·법인·역할은 여기서 받지 않는다. 그 셋은 <b>조회 범위 그 자체</b>라
     * ({@code AppUser.scopeKey}, {@code DashboardService} 법인 기준, {@code SdePoolService} 팀 기준)
     * 마이페이지에서 고칠 수 있게 하면 <b>자기 조회 범위를 자기가 바꾸는 경로</b>가 열린다.
     * 사용자 요구("리더 외 다른 구분자는 수정 항목 없음", 2026-09-09)와도 같은 결론이다.
     */
    @Transactional
    public void updateProfile(UserSession session, ProfileUpdateRequest req) {
        AppUser u = userRepo.findById(session.getUserId()).orElseThrow();
        if (!"SDE_LEADER".equals(u.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "정/부는 SDE 리더만 설정할 수 있습니다.");
        String rank = leaderRank(req.leaderRank());
        if (rank == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "정/부 값이 올바르지 않습니다.");
        u.setLeaderRank(rank);
        userRepo.save(u);
        log.info("정/부 변경 — loginId={} leaderRank={}", u.getLoginId(), rank);
    }

    /** 정/부 정규화. 아는 값이 아니면 null — 모르는 값을 그대로 저장하면 화면에 그대로 새어 나온다. */
    private static String leaderRank(String v) {
        String t = trim(v);
        if (t == null) return null;
        String up = t.toUpperCase();
        return LEADER_RANKS.contains(up) ? up : null;
    }

    /**
     * 회원가입 화면의 소속 후보. 법인/팀 마스터가 ITSM 에 없어 우리가 모아 준다.
     *
     * <p><b>법인 후보에 ITSM 관측값(`req_comp_nm`)을 먼저 넣는다.</b> 조회 범위를 법인명 문자열로
     * 맞추게 되므로(코드가 없다), 사용자가 손으로 다르게 적으면 **조용히 0건**이 된다.
     * 관측값을 고르게 하는 것이 그 사고를 막는 가장 싼 방법이다.
     */
    public MetaResponse meta() {
        Set<String> corps = new LinkedHashSet<>();
        reqRepo.findAll().stream().map(ItsmRequest::getReqCompNm)
                .filter(AuthService::notBlank).sorted().forEach(corps::add);   // ITSM 관측값 우선
        userRepo.findByRole("SME").stream().map(AppUser::getCorpNm)
                .filter(AuthService::notBlank).sorted().forEach(corps::add);

        Set<String> teams = new LinkedHashSet<>(sdeProps.getTeams());          // yml 에 선언한 팀 우선
        Stream.concat(userRepo.findByRole("SDE_LEADER").stream(), userRepo.findByRole("SDE").stream())
                .map(AppUser::getTeam).filter(AuthService::notBlank).sorted().forEach(teams::add);
        return new MetaResponse(List.copyOf(corps), List.copyOf(teams));
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    private String authMessage(ItsmClient.LoginResult r) {
        String m = r.message() == null || r.message().isBlank() ? "ITSM 인증에 실패했습니다." : r.message();
        return r.authFailed()
                ? m + " (ID/비밀번호를 확인하세요. 반복 실패 시 ITSM 계정이 잠길 수 있습니다)"
                : "ITSM 서버에 연결하지 못했습니다: " + m;
    }

    private LoginResponse fail(String msg) {
        return new LoginResponse(false, null, null, null, null, msg, false);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
