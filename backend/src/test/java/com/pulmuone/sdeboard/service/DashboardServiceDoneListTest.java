package com.pulmuone.sdeboard.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료 목록의 **기간 경계**를 고정한다 — 양쪽 다 포함이다.
 * "이번 주"의 월요일 00:00 건과 오늘 23:59 건이 빠지면 주간보고에서 그대로 사라진다.
 *
 * (완료일을 *어떻게 정하는지* 는 `StatusBackfillTest` 가 맡는다 — 대상이 다르면 테스트도 나눈다.)
 */
class DashboardServiceDoneListTest {

    @Test
    void 기간_경계는_양쪽_다_포함한다() {
        LocalDate from = LocalDate.of(2026, 9, 7), to = LocalDate.of(2026, 9, 13);
        assertThat(DashboardService.inPeriod(from.atStartOfDay(), from, to)).isTrue();          // 월 00:00
        assertThat(DashboardService.inPeriod(to.atTime(23, 59, 59), from, to)).isTrue();        // 일 23:59
        assertThat(DashboardService.inPeriod(from.minusDays(1).atTime(23, 59), from, to)).isFalse();
        assertThat(DashboardService.inPeriod(to.plusDays(1).atStartOfDay(), from, to)).isFalse();
        // 경계가 없으면 전부 통과, 완료일이 없는 건은 기간을 걸면 빠진다
        assertThat(DashboardService.inPeriod(null, null, null)).isTrue();
        assertThat(DashboardService.inPeriod(null, from, to)).isFalse();
    }
}
