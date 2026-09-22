package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;

import com.pulmuone.sdeboard.service.AuthService;
import com.pulmuone.sdeboard.service.DashboardService;
import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 회원가입 / 로그인 / 로그아웃.
 * 인증은 **ITSM 계정 로그인**으로 한다. ITSM 비밀번호는 저장하지 않는다(세션 메모리 전용).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final DashboardService dashboard;
    private final SessionSupport sessionSupport;

    @PostMapping("/signup")
    public ResponseEntity<LoginResponse> signup(@RequestBody SignupRequest req) {
        LoginResponse res = auth.signup(req);
        return res.success() ? ResponseEntity.ok(res) : ResponseEntity.status(HttpStatus.BAD_REQUEST).body(res);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        LoginResponse res = auth.login(req);
        return res.success() ? ResponseEntity.ok(res) : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(res);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        auth.logout(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** 회원가입 화면용 기존 법인/팀 목록 (인증 불필요) */
    @GetMapping("/meta")
    public MetaResponse meta() {
        return auth.meta();
    }

    @GetMapping("/me")
    public MeResponse me(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.me(s);
    }

    /**
     * 마이페이지 저장 — **정/부(`leaderRank`)만** 받는다.
     * 팀·법인·역할은 조회 범위 그 자체라 여기서 열지 않는다(자기 범위를 자기가 바꾸게 된다).
     * 응답은 갱신된 `me` 전체다 — 화면이 사이드바 배지까지 한 번에 다시 그린다.
     */
    @PatchMapping("/me")
    public MeResponse updateMe(@RequestHeader(value = "X-Session", required = false) String sessionId,
                          @RequestBody ProfileUpdateRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        auth.updateProfile(s, req);
        return dashboard.me(s);
    }
}
