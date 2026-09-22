package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.service.RequestStatusService;
import com.pulmuone.sdeboard.service.ScheduleService;
import com.pulmuone.sdeboard.web.dto.ScheduleDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 요청 처리 일정 + 추적불가 확정 (`UR-260922-1`). 전부 로그인 세션 범위로 제한된다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService schedules;
    private final RequestStatusService statuses;
    private final SessionSupport sessionSupport;

    /** 캘린더 — `from`/`to` 는 `2026-09-21T00:00:00` 형식(서울 시각). */
    @GetMapping("/schedules")
    public CalendarResponse calendar(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        UserSession s = sessionSupport.require(sessionId);
        return schedules.calendar(s, from, to);
    }

    /** 등록 화면의 작업자 후보(본인 + 할당할 수 있는 사람). */
    @GetMapping("/schedules/assignees")
    public List<AssigneeOption> assignees(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        return schedules.assignees(sessionSupport.require(sessionId));
    }

    @PostMapping("/schedules")
    public ScheduleView create(@RequestHeader(value = "X-Session", required = false) String sessionId,
                               @RequestBody ScheduleCreateRequest body) {
        return schedules.create(sessionSupport.require(sessionId), body);
    }

    @PutMapping("/schedules/{id}")
    public ScheduleView update(@RequestHeader(value = "X-Session", required = false) String sessionId,
                               @PathVariable Long id, @RequestBody ScheduleUpdateRequest body) {
        return schedules.update(sessionSupport.require(sessionId), id, body);
    }

    /** 취소 — 사유 필수. DELETE 는 본문이 애매해 POST 로 둔다. */
    @PostMapping("/schedules/{id}/cancel")
    public Map<String, Object> cancel(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                      @PathVariable Long id, @RequestBody ScheduleCancelRequest body) {
        schedules.cancel(sessionSupport.require(sessionId), id, body);
        return Map.of("ok", true);
    }

    /** 추적불가 → 작업완료 확정(사람이 확인). */
    @PostMapping("/requests/{reqNo}/complete")
    public Map<String, Object> complete(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                        @PathVariable String reqNo) {
        statuses.completeUntracked(sessionSupport.require(sessionId), reqNo);
        return Map.of("ok", true);
    }
}
