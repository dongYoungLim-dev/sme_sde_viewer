package com.pulmuone.sdeboard.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

/**
 * 이 시스템의 **시각 기준 하나**. 우리가 찍는 모든 관측 시각은 여기를 지난다.
 *
 * <p>왜 필요했나 — 컨테이너(`maven:3.9-eclipse-temurin-21`)의 기본 시간대가 <b>Etc/UTC</b> 라,
 * {@code LocalDateTime.now()} 가 서울보다 <b>9시간 이른</b> 값을 찍고 있었다.
 * ITSM 에서 받은 날짜는 {@code ItsmResponseMapper} 가 KST 를 명시해 변환하고 있어 멀쩡했고,
 * <b>우리가 찍는 값만</b>(done_at · first_seen · last_seen · observed_at · synced_at · updated_at) 틀렸다.
 *
 * <p>고치는 방법이 둘이었다 — 컨테이너에 {@code TZ} 를 주거나, 코드가 시간대를 명시하거나.
 * <b>둘 다 한다.</b> 환경변수만 두면 다른 곳에 배포하는 순간 조용히 되돌아가고,
 * 그때 증상은 "화면 시각이 좀 이상하다" 뿐이라 원인까지 가는 데 오래 걸린다.
 *
 * <p>바꿔야 하면 {@code APP_TIMEZONE} 환경변수(또는 {@code -Dapp.timezone})로 준다.
 */
public final class AppTime {

    /** 기본은 서울. 환경변수로만 바꾼다 — 코드에 흩어진 상수를 만들지 않는다. */
    public static final ZoneId ZONE = ZoneId.of(
            System.getProperty("app.timezone",
                    System.getenv().getOrDefault("APP_TIMEZONE", "Asia/Seoul")));

    private AppTime() {}

    /**
     * JVM 기본 시간대를 이 값으로 고정한다. {@code main()} 에서 <b>스프링 기동 전에</b> 부른다.
     * 우리 코드는 {@link #now()} 를 쓰지만, 프레임워크·드라이버가 기본 시간대를 보는 곳이 남아 있다.
     */
    public static void applyAsJvmDefault() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }

    /**
     * 지금 시각(서울 기준). {@code LocalDateTime.now()} 대신 이것을 쓴다.
     *
     * <p><b>초 단위로 자른다.</b> {@code LocalDateTime.now()} 는 마이크로초까지 들고 오는데
     * 우리 시각 컬럼은 전부 {@code DATETIME}(소수점 자리수 0) 이다. 자르지 않으면
     * <b>방금 찍어 저장한 값과 DB 에서 다시 읽은 값이 다르다</b> — 소수점이 DB 에서 사라지므로.
     *
     * <p>그 차이로 실제로 고장이 났다(2026-09-10): 분석 노트의 낙관적 잠금 키가
     * {@code updated_at} 이라, 저장 응답(메모리 값 = 소수점 있음)을 그대로 되돌려 보낸 두 번째 저장이
     * DB 값(소수점 없음)과 달라 <b>혼자 쓰고 있는데도 409 CONFLICT</b> 가 났다.
     * 화면을 새로 열기 전까지 계속 실패해서, "다른 사람이 먼저 저장했습니다" 라는 거짓 안내만 반복됐다.
     *
     * <p>고칠 자리가 셋이었다 — 비교하는 곳에서 자르기 · 컬럼을 {@code DATETIME(6)} 으로 올리기 · 여기.
     * <b>여기가 맞다.</b> 비교하는 곳에서 자르면 같은 함정이 다음 비교에 또 남고,
     * 컬럼을 올리면 JVM 이 나노초를 주는 환경에서 같은 불일치가 되살아난다.
     * 찍는 값과 저장되는 값을 애초에 같게 만드는 것이 이 클래스가 할 일이다.
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    /** 오늘(서울 기준). {@code LocalDate.now()} 대신 이것을 쓴다. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
