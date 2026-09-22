package com.pulmuone.sdeboard.security;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 로그인 세션 (서버 메모리 전용).
 *
 * 보안 원칙 — 결정된 A안:
 *  · ITSM 비밀번호는 **DB·로그·응답 어디에도 남기지 않는다.** 이 객체(메모리)에서만 세션 수명 동안 보관한다.
 *  · 보관 이유: accessToken 이 30분이라, 세션 동안 폴링을 유지하려면 재로그인이 필요하다.
 *    보관을 원치 않으면 itsm.session.keep-credential=false → 30분 뒤 폴링이 멈추고 재로그인을 요구한다.
 *  · 프로세스 재시작 시 전부 사라진다(=밤사이 수집 없음, 의도된 동작).
 */
@Getter @Setter
public class UserSession {
    private String sessionId;
    private Long userId;
    private String loginId;
    private String name;
    private String role;

    private String itsmAccessToken;
    private String itsmRefreshToken;
    private LocalDateTime tokenIssuedAt;

    /** 세션 메모리 전용 자격증명 (keep-credential=false 면 null) */
    private transient char[] itsmCredential;

    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime lastSyncAt;
    private String syncState = "IDLE";      // IDLE / OK / AUTH_FAILED / ERROR
    private String syncMessage;

    public String credential() {
        return itsmCredential == null ? null : new String(itsmCredential);
    }

    /** 로그아웃/만료 시 자격증명·토큰을 메모리에서 지운다. */
    public void wipe() {
        if (itsmCredential != null) {
            java.util.Arrays.fill(itsmCredential, '\0');
            itsmCredential = null;
        }
        itsmAccessToken = null;
        itsmRefreshToken = null;
    }
}
