package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.SyncLog;
import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 수동 동기화 — **내 세션(내 ITSM 계정)** 기준으로만 돈다. */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;
    private final SessionSupport sessionSupport;
    private final ItsmProperties props;

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        UserSession s = sessionSupport.require(sessionId);
        if (props.isMock()) {
            return ResponseEntity.ok(Map.of("skipped", true,
                    "message", "mock 모드 — 실제 동기화를 하지 않습니다. ITSM_MOCK=false 로 전환하세요."));
        }
        SyncLog log = syncService.syncSession(s);
        return ResponseEntity.ok(log);
    }
}
