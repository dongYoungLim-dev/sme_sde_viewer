package com.pulmuone.sdeboard.web.dto;

import java.util.List;
import java.util.Map;

/** KPI·상태분포·담당자현황 — 대시보드 통계 응답. */
public final class StatsDtos {

    private StatsDtos() {}

    /**
     * waiting/inProgress/untracked/done = 이 보드의 4단계 상태별 건수(2026-09-22).
     * closed = ITSM 할 일에서 내려간 건, open = 아직 끝나지 않은 건(대기·작업중만).
     * <p>{@code newComments} 는 <b>내가 아직 안 본 코멘트가 달린 요청 수</b>(코멘트 수가 아니다) —
     * 상태 셀렉트의 `새 댓글` 항목이 이 값 하나로 산다. ⚠️ 보는 사람마다 다른 값이다.
     */
    public record KpiView(int total, int intake, int leaderPending,
                          int waiting, int inProgress, int untracked,
                          int late, int soon, int done, int closed, int open, int newComments) {}

    public record DistView(String status, long count) {}

    /**
     * 담당자 한 줄.
     * <p>{@code active}·{@code byStatus} 는 <b>진행 중만</b> 센다(완료 제외). {@code done} 은 그 바깥이다 —
     * 표의 합계에 섞이면 "지금 몇 건 들고 있나" 를 못 읽는다(사용자 요청 2026-09-11).
     */
    public record TeamView(String perId, String name, int active, int late, int done,
                           Map<String, Integer> byStatus) {}

    public record StatsResponse(String role, KpiView kpis, List<DistView> dist, List<TeamView> team) {}
}
