package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.web.dto.ItsmDtos.*;

import com.pulmuone.sdeboard.adapter.itsm.ItsmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * ITSM API 확인용 (Phase 0). 사내망에서 실제 응답 구조·첨부 유무를 확인한다.
 * 로그인 → 지정 엔드포인트 호출 → ITSM 원시 JSON 그대로 반환.
 */
@RestController
@RequestMapping("/api/itsm")
@RequiredArgsConstructor
public class ItsmVerifyController {

    private final ItsmClient client;

    @PostMapping("/verify")
    public VerifyResponse verify(@RequestBody VerifyRequest req) {
        ItsmClient.LoginResult login = client.login(req.username(), req.password());
        if (!login.success()) {
            return new VerifyResponse(false, login.message(), 0, false, login.rawJson());
        }
        ItsmClient.RawResult res = client.call(req.endpoint(), login.accessToken(), req.payload());
        return new VerifyResponse(true, login.message(), res.status(), res.ok(), res.body());
    }
}
