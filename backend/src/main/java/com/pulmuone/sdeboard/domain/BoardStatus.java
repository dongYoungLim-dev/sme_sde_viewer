package com.pulmuone.sdeboard.domain;

import java.util.Set;

/**
 * 이 보드가 요청에 매기는 **4단계 상태** — `itsm_request.work_status` 에 저장된다.
 *
 * <p>2026-09-22 (`UR-260922-1`) 로 ITSM 11단계 파이프라인 파생 버킷(NEW/ANAL/DEV/TEST/DEP/HOLD)을 **폐기**하고
 * 이 넷으로 교체했다. 상태의 근거는 ITSM 상태명이 아니라 <b>이 보드의 스케줄 유무 + ITSM To-Do 잔존 여부</b>다.
 *
 * <pre>
 *  WAITING ──스케줄 등록──▶ IN_PROGRESS ──To-Do 에서 사라짐──▶ DONE
 *     ▲                        │
 *     └────── 스케줄 취소 ─────┘
 *  WAITING ──To-Do 에서 사라짐(스케줄 없이)──▶ UNTRACKED ──사람이 확정──▶ DONE
 * </pre>
 *
 * <p>컬럼 이름을 `board_status` 로 새로 만들지 않고 `work_status` 를 그대로 쓴다 — 완료(`DONE`) 판정·
 * `done_at`·완료 목록·읽기 전용 규칙이 전부 이 문자열 하나에 기대고 있어, 값만 바꾸면 그대로 살아남는다.
 */
public final class BoardStatus {
    private BoardStatus() {}

    public static final String WAITING = "WAITING";          // 대기
    public static final String IN_PROGRESS = "IN_PROGRESS";  // 작업중
    public static final String DONE = "DONE";                // 작업완료
    public static final String UNTRACKED = "UNTRACKED";      // 추적불가

    /** 화면·집계가 도는 순서. **유일한 출처**다. */
    public static final java.util.List<String> ORDER = java.util.List.of(WAITING, IN_PROGRESS, UNTRACKED, DONE);

    /** 종료된 것으로 보는 상태 — 지연·임박 판정, 진행 중 집계에서 뺀다. */
    private static final Set<String> CLOSED = Set.of(DONE, UNTRACKED);

    public static boolean isClosed(String s) { return s != null && CLOSED.contains(s); }

    /** 4단계 값인가. 아니면 폐기된 예전 버킷(NEW/ANAL/DEV/TEST/DEP/HOLD)이거나 null 이다 — 백필 대상. */
    public static boolean isCurrent(String s) { return s != null && ORDER.contains(s); }

    public static String label(String s) {
        if (s == null) return "-";
        return switch (s) {
            case WAITING -> "대기";
            case IN_PROGRESS -> "작업중";
            case DONE -> "작업완료";
            case UNTRACKED -> "추적불가";
            default -> s;
        };
    }
}
