package com.pulmuone.sdeboard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 설정.
 *
 * 정상 경로는 **Vite 프록시**(브라우저 → :5173/api → 백엔드)라 CORS가 필요 없다.
 * 다만 원격(Tailscale 등)에서 백엔드(:8080)를 직접 호출하는 경우를 위해 오리진 '패턴'을 허용한다.
 * allowedOrigins 는 와일드카드/포트 패턴을 못 쓰므로 allowedOriginPatterns 를 사용한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors-allowed-origin-patterns}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                // ⚠️ 새 메서드를 쓰기 시작하면 **여기에도 추가해야 한다.**
                //    빠지면 프록시(:5173) 경유는 멀쩡한데 **원격에서 백엔드를 직접 부를 때만** preflight 가 403 이 된다
                //    — 서버 로그에 아무것도 안 남아서 화면만 보면 원인을 못 찾는다.
                //    (2026-09-09: PATCH 누락으로 마이페이지 정/부 저장이 실패했다)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")          // X-Session 커스텀 헤더 → preflight 통과에 필요
                .allowCredentials(false)      // 세션은 쿠키가 아니라 X-Session 헤더로 전달
                .maxAge(3600);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
