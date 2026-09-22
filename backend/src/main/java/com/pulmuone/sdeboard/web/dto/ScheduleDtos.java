package com.pulmuone.sdeboard.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 요청 처리 일정 API 메시지 (`UR-260922-1`). */
public final class ScheduleDtos {

    private ScheduleDtos() {}

    /**
     * 일정 등록. `assigneeId` 가 null 이면 **본인**이다(SDE 는 항상 본인).
     * SME·리더는 조회 범위 안의 SDE 를 지정해 **할당**할 수 있다.
     */
    public record ScheduleCreateRequest(String reqNo, LocalDateTime start, LocalDateTime end, Long assigneeId) {}

    /** 일정 수정 — 시간·작업자를 고친다. **사유 필수**. */
    public record ScheduleUpdateRequest(LocalDateTime start, LocalDateTime end, Long assigneeId, String reason) {}

    /** 일정 취소 — **사유 필수**. 요청은 대기로 돌아간다. */
    public record ScheduleCancelRequest(String reason) {}

    /**
     * 일정 한 줄. `status` = ACTIVE / REVISED(수정으로 대체됨) / CANCELLED. `reason` 은 **그 일정이 끝난 이유**다.
     * 캘린더용 필드(`title`, `reqStatus`)를 함께 싣는다.
     */
    public record ScheduleView(Long id, String reqNo, String title, String reqStatus,
                               Long assigneeId, String assigneeName,
                               Long createdBy, String createdByName,
                               LocalDateTime start, LocalDateTime end,
                               String status, String reason,
                               Long closedBy, String closedByName, LocalDateTime closedAt,
                               LocalDateTime createdAt) {}

    /** 캘린더 응답 — 기간 안의 유효한 일정. */
    public record CalendarResponse(List<ScheduleView> items, LocalDateTime from, LocalDateTime to) {}

    /** 등록 화면의 작업자 후보. `me=true` 가 본인. */
    public record AssigneeOption(Long id, String name, String role, String team, boolean me) {}
}
