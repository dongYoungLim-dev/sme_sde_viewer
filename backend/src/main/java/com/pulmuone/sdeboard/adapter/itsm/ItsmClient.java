package com.pulmuone.sdeboard.adapter.itsm;

import java.util.Map;

/**
 * ITSM 부패방지계층(ACL). 도메인/서비스 코드는 오직 이 인터페이스에만 의존한다.
 * 실제 API 스펙이 달라져도 구현체(RealItsmClient)와 매퍼만 손대면 된다.
 */
public interface ItsmClient {

    /** /ims/login → 토큰 발급. 성공/실패 모두 HTTP 200이므로 data.code/result로 판정. */
    LoginResult login(String username, String password);

    /** 임의 엔드포인트 POST 호출 후 원시 응답 반환 (동기화·API 확인 공용). */
    RawResult call(String path, String bearerToken, Map<String, Object> payload);

    /**
     * 로그인 결과.
     * accessToken 30분 / refreshToken 8시간(실측), perId·compCd 는 토큰 payload 에서 추출.
     * authFailed=true 는 ID/PW 가 틀렸다는 뜻 — 재시도하면 계정이 잠길 수 있으므로 재시도 금지.
     */
    record LoginResult(boolean success, boolean authFailed,
                       String accessToken, String refreshToken,
                       String perId, String compCd,
                       String message, String rawJson) {
        public String token() { return accessToken; }
    }

    record RawResult(int status, boolean ok, String body) {}
}
