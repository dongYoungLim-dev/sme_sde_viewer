package com.pulmuone.sdeboard.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ITSM 연동 설정. application.yml 의 itsm.* 를 바인딩한다.
 * 실제 서버 전환/스펙 변경 시 이 값(환경변수)만 바꾸면 된다.
 */
@Component
@ConfigurationProperties(prefix = "itsm")
@Getter
@Setter
public class ItsmProperties {
    private boolean mock = true;
    private String baseUrl;
    private String origin;
    private Endpoints endpoints = new Endpoints();
    private Headers headers = new Headers();
    private Poll poll = new Poll();
    private Session session = new Session();

    /** ITSM 상태코드(staCd) → 대시보드 버킷 강제 매핑(override). 보통은 flow 로 충분하다. */
    private Map<String, String> statusMap = new LinkedHashMap<>();

    /**
     * 변경 요청 상태의 **진행 순서**. 진행률은 오직 여기서 나온다(버킷에서 뽑으면 순서가 역행한다).
     * 새 상태가 관측되면 seq 를 맞춰 한 줄 추가하면 끝 — 코드 수정 불필요.
     */
    private List<FlowStep> flow = new ArrayList<>();

    /** 이 순번부터는 '할당 요청됨'으로 본다(기본 2 = 공정할당요청). */
    private int assignFromSeq = 2;

    @Getter @Setter
    public static class FlowStep {
        private int seq;             // 진행 순서 (1부터)
        private String code;         // ITSM staCd — 확인된 것만
        private String name;         // ITSM staNm — 매칭 키(공백 무시 비교)
        private String bucket;       // NEW/ANAL/DEV/TEST/DEP/DONE/HOLD (색·집계용)
        private int progress;        // 표시 진행률 %
    }

    @Getter @Setter
    public static class Endpoints {
        private String login;
        private String myTodos;
        private String processingDetail;
        private String directDetail;
    }
    @Getter @Setter
    public static class Headers {
        private String acceptLanguage = "ko_KR";
        private String timeZone = "+09:00";
    }
    /**
     * 로그인 세션 정책 (A안 — ITSM 비밀번호 미저장).
     * keepCredential=true 면 **메모리 세션 안에서만** 자격증명을 들고 있다가
     * accessToken(30분) 만료 시 재로그인해 폴링을 이어간다. DB·로그에는 절대 남지 않는다.
     */
    @Getter @Setter
    public static class Session {
        private boolean keepCredential = true;
        private int idleMinutes = 120;     // 미사용 만료
        private int maxMinutes = 480;      // 절대 만료 = refreshToken 수명(8시간)
    }
    @Getter @Setter
    public static class Poll {
        private boolean enabled = true;
        private long intervalMs = 300000;
        private int pageSize = 50;      // itemPerPage
        private int maxPages = 20;      // 페이징 안전장치
    }
}
