package com.pulmuone.sdeboard.security;

import com.pulmuone.sdeboard.domain.AppTime;

import com.pulmuone.sdeboard.config.ItsmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 로그인 세션 보관소 (in-memory). 재기동하면 모두 사라진다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRegistry {

    private final ItsmProperties props;
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public UserSession create(Long userId, String loginId, String name, String role,
                              String accessToken, String refreshToken, char[] credential) {
        UserSession s = new UserSession();
        s.setSessionId(newId());
        s.setUserId(userId);
        s.setLoginId(loginId);
        s.setName(name);
        s.setRole(role);
        s.setItsmAccessToken(accessToken);
        s.setItsmRefreshToken(refreshToken);
        s.setTokenIssuedAt(AppTime.now());
        s.setItsmCredential(props.getSession().isKeepCredential() ? credential : null);
        s.setCreatedAt(AppTime.now());
        s.setLastSeenAt(AppTime.now());
        sessions.put(s.getSessionId(), s);
        return s;
    }

    /** 유효한 세션 조회 + 최근 사용시각 갱신. 만료면 null. */
    public UserSession touch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        UserSession s = sessions.get(sessionId);
        if (s == null) return null;
        if (expired(s)) { remove(sessionId); return null; }
        s.setLastSeenAt(AppTime.now());
        return s;
    }

    public Collection<UserSession> active() {
        purge();
        return List.copyOf(sessions.values());
    }

    /**
     * 세션 제거. **이미 없는(또는 null) 세션이면 조용히 넘어간다.**
     *
     * <p>로그아웃은 세션이 이미 사라진 뒤에도 눌린다 — 서버 재기동 직후, 만료 후, 두 번 누를 때.
     * {@code ConcurrentHashMap.remove(null)} 은 NPE 라 그대로 두면 <b>로그아웃이 500 으로 실패</b>하고
     * 브라우저에 세션이 남는다. 지우려는 요청이 지울 게 없어서 실패하는 것은 말이 안 된다.
     */
    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        UserSession s = sessions.remove(sessionId);
        if (s != null) s.wipe();
    }

    public void purge() {
        sessions.values().removeIf(s -> {
            boolean gone = expired(s);
            if (gone) s.wipe();
            return gone;
        });
    }

    private boolean expired(UserSession s) {
        ItsmProperties.Session cfg = props.getSession();
        LocalDateTime now = AppTime.now();
        boolean tooOld = s.getCreatedAt().plus(Duration.ofMinutes(cfg.getMaxMinutes())).isBefore(now);
        boolean idle = s.getLastSeenAt().plus(Duration.ofMinutes(cfg.getIdleMinutes())).isBefore(now);
        return tooOld || idle;
    }

    private String newId() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
