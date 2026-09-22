package com.pulmuone.sdeboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 허용 메서드를 고정한다.
 *
 * <p>이 목록이 새 메서드를 빠뜨리면 <b>프록시(:5173) 경유는 멀쩡한데 원격에서 백엔드를 직접 부를 때만</b>
 * preflight 가 403 이 된다. 서버 로그에 아무것도 남지 않아 화면만 보면 원인을 찾을 수 없다.
 * (2026-09-09 실제로 {@code PATCH} 누락으로 마이페이지 정/부 저장이 실패했다)
 */
class WebConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void 쓰는_메서드는_전부_허용된다() {
        WebConfig cfg = new WebConfig();
        ReflectionTestUtils.setField(cfg, "allowedOriginPatterns", new String[]{ "http://localhost:*" });

        CorsRegistry registry = new CorsRegistry();
        cfg.addCorsMappings(registry);

        Map<String, CorsConfiguration> configs =
                (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        CorsConfiguration api = configs.get("/api/**");

        assertThat(api).isNotNull();
        // 컨트롤러가 실제로 쓰는 메서드 전부 — 하나라도 빠지면 원격 접속에서만 조용히 막힌다
        assertThat(api.getAllowedMethods())
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
