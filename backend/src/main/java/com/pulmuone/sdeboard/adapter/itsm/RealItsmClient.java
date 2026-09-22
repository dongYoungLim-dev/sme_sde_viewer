package com.pulmuone.sdeboard.adapter.itsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulmuone.sdeboard.config.ItsmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * ItsmClient 실제 구현. RestTemplate 로 ITSM externalapi 호출.
 * Origin/accept-language/time-zone 헤더를 문서 안내대로 명시한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealItsmClient implements ItsmClient {

    private final ItsmProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper om;

    private String url(String path) {
        String base = props.getBaseUrl().replaceAll("/+$", "");
        return base + path;
    }

    private HttpHeaders baseHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        h.set("Origin", props.getOrigin());                 // 문서: 수동 명시 필수
        h.set("accept-language", props.getHeaders().getAcceptLanguage());
        h.set("time-zone", props.getHeaders().getTimeZone());
        if (token != null && !token.isBlank()) h.setBearerAuth(token);
        return h;
    }

    @Override
    public LoginResult login(String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    url(props.getEndpoints().getLogin()), HttpMethod.POST,
                    new HttpEntity<>(body, baseHeaders(null)), String.class);
            String raw = res.getBody();
            JsonNode root = om.readTree(raw == null ? "{}" : raw);
            JsonNode data = root.path("data");
            String access = extractToken(data, "accessToken");
            String refresh = extractToken(data, "refreshToken");
            boolean ok = access != null && !access.isBlank();
            String msg = data.path("message").asText(root.path("status").asText(""));
            // 성공/실패 모두 HTTP 200 → data.result/code 로 판정. 토큰이 없으면 자격증명 문제로 본다.
            boolean authFailed = !ok;
            Map<String, String> claims = decodeJwtClaims(access);
            return new LoginResult(ok, authFailed, access, refresh,
                    claims.get("perId"), claims.get("compCd"), msg, raw);
        } catch (RestClientResponseException e) {
            String errBody = e.getResponseBodyAsString();
            boolean authFailed = e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403;
            return new LoginResult(false, authFailed, null, null, null, null,
                    "HTTP " + e.getStatusCode() + " " + errBody, errBody);
        } catch (Exception e) {
            log.warn("ITSM login 호출 실패: {}", e.getMessage());
            // 네트워크/서버 오류는 자격증명 문제가 아니다 → 재시도 가능
            return new LoginResult(false, false, null, null, null, null, e.getMessage(), null);
        }
    }

    /** JWT payload 에서 perId·compCd 추출 (서명 검증 목적 아님 — 표시/매핑용). */
    private Map<String, String> decodeJwtClaims(String jwt) {
        Map<String, String> out = new HashMap<>();
        try {
            if (jwt == null) return out;
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return out;
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode n = om.readTree(json);
            if (n.hasNonNull("perId")) out.put("perId", n.get("perId").asText());
            if (n.hasNonNull("compCd")) out.put("compCd", n.get("compCd").asText());
        } catch (Exception ignore) { }
        return out;
    }

    /** data.token 이 문자열이거나 {accessToken, refreshToken} 객체일 수 있어 방어적으로 추출. */
    private String extractToken(JsonNode data, String kind) {
        JsonNode t = data.path("token");
        boolean access = "accessToken".equals(kind);
        if (t.isTextual()) return access ? t.asText() : null;
        if (t.isObject()) {
            if (t.hasNonNull(kind)) return t.get(kind).asText();
            String snake = access ? "access_token" : "refresh_token";
            if (t.hasNonNull(snake)) return t.get(snake).asText();
        }
        if (data.hasNonNull(kind)) return data.get(kind).asText();
        return null;
    }

    @Override
    public RawResult call(String path, String bearerToken, Map<String, Object> payload) {
        try {
            ResponseEntity<String> res = restTemplate.exchange(
                    url(path), HttpMethod.POST,
                    new HttpEntity<>(payload == null ? Map.of() : payload, baseHeaders(bearerToken)),
                    String.class);
            return new RawResult(res.getStatusCode().value(), res.getStatusCode().is2xxSuccessful(), res.getBody());
        } catch (RestClientResponseException e) {
            return new RawResult(e.getStatusCode().value(), false, e.getResponseBodyAsString());
        } catch (Exception e) {
            return new RawResult(0, false, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
