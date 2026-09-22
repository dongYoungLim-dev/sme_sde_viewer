package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 요청건의 **처리 일정** — 이 보드가 원본을 갖는 데이터다(ITSM 에 없다).
 *
 * <p>담당자 표시(`itsm_request.assignee_name`, ITSM 값)를 **바꾸지 않는다**(사용자 결정 2026-09-22).
 * 스케줄은 "이 건을 언제 누가 처리하나" 를 적고, 그 유무로 요청 상태(대기/작업중)를 바꾸는 용도다.
 *
 * <p>한 요청에 <b>ACTIVE 는 최대 하나</b>다(서비스에서 검사). 끝난 일정은 지우지 않고 행으로 남긴다:
 * <ul>
 *   <li>{@code ACTIVE} — 지금 유효한 일정</li>
 *   <li>{@code REVISED} — 시간·작업자를 <b>고쳐서</b> 새 행으로 대체됨. {@code reason} = 수정 사유</li>
 *   <li>{@code CANCELLED} — <b>취소</b>됨(→ 요청은 대기로 복귀). {@code reason} = 취소 사유</li>
 * </ul>
 */
@Entity
@Table(name = "request_schedule")
@Getter @Setter @NoArgsConstructor
public class RequestSchedule {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVISED = "REVISED";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    /** 실제 작업자(`app_user.id`). 등록자와 같을 수도, SME·리더가 지정한 SDE 일 수도 있다. */
    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    /** 등록 버튼을 누른 사람(`app_user.id`). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "start_dt", nullable = false)
    private LocalDateTime startDt;

    @Column(name = "end_dt", nullable = false)
    private LocalDateTime endDt;

    @Column(name = "status", nullable = false, length = 12)
    private String status = ACTIVE;

    /** 이 일정이 **끝난 이유**(수정·취소 사유). ACTIVE 인 동안은 null. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** 이 행을 끝낸(수정·취소한) 사람과 시각. */
    @Column(name = "closed_by")
    private Long closedBy;
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 수정으로 만들어진 행이면 대체한 이전 행의 id. */
    @Column(name = "prev_id")
    private Long prevId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
