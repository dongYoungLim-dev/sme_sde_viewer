package com.pulmuone.sdeboard.web.dto;

import java.util.Map;

/** ITSM 실제 응답 확인용(Phase 0). 사내망에서 스펙을 확정할 때만 쓴다. */
public final class ItsmDtos {

    private ItsmDtos() {}

    public record VerifyRequest(String username, String password, String endpoint, Map<String, Object> payload) {}

    public record VerifyResponse(boolean loginOk, String loginMessage, int httpStatus,
                                 boolean callOk, String rawBody) {}
}
