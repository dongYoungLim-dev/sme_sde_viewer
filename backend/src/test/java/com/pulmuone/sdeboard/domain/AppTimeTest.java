package com.pulmuone.sdeboard.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시각의 기준이 **서울**임을 고정한다.
 *
 * <p>이 테스트가 지키는 사고: 컨테이너 기본 시간대가 UTC 라 우리가 찍는 관측 시각
 * (done_at · first_seen · observed_at · synced_at)이 9시간 이르게 저장되고 있었다.
 * 화면에는 "9시간 전"처럼 보일 뿐이라 원인까지 가는 데 오래 걸리는 종류의 고장이다.
 */
class AppTimeTest {

    @Test
    void 기준_시간대는_서울이다() {
        assertThat(AppTime.ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void now_는_JVM_기본_시간대와_무관하게_서울_시각이다() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));   // 사고 당시의 컨테이너 상태
            LocalDateTime seoul = LocalDateTime.ofInstant(Instant.now(), ZoneId.of("Asia/Seoul"));
            assertThat(Duration.between(AppTime.now(), seoul).abs()).isLessThan(Duration.ofSeconds(5));
            assertThat(AppTime.today()).isEqualTo(seoul.toLocalDate());
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void now_는_소수점_이하가_없다() {
        // 시각 컬럼이 전부 DATETIME(소수점 자리수 0) 이라, 여기서 자르지 않으면
        // **찍어 저장한 값과 DB 에서 다시 읽은 값이 다르다.**
        // 그 차이로 분석 노트의 낙관적 잠금(updated_at)이 혼자 쓰는데도 409 를 냈다(2026-09-10).
        for (int i = 0; i < 50; i++) {          // 우연히 0 이 나오는 순간을 통과시키지 않게 여러 번
            assertThat(AppTime.now().getNano()).isZero();
        }
    }

    @Test
    void applyAsJvmDefault_는_JVM_기본값까지_서울로_맞춘다() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/UTC"));
            AppTime.applyAsJvmDefault();
            assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
            // 프레임워크·드라이버가 기본 시간대를 보는 경로도 함께 맞아야 한다
            assertThat(LocalDateTime.now()).isCloseTo(AppTime.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long n, java.time.temporal.ChronoUnit u) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(n, u);
    }
}
