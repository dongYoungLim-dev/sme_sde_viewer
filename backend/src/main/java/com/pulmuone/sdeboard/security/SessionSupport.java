package com.pulmuone.sdeboard.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 컨트롤러에서 세션을 강제하는 헬퍼. 없으면 401. */
@Component
@RequiredArgsConstructor
public class SessionSupport {

    private final SessionRegistry registry;

    public UserSession require(String sessionId) {
        UserSession s = registry.touch(sessionId);
        if (s == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "세션이 없거나 만료되었습니다. 다시 로그인하세요.");
        return s;
    }
}
