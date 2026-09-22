package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 폴링으로 감지한 상태/담당 변화 이력 — 대시보드 타임라인의 근거. */
@Entity
@Table(name = "request_status_history")
@Getter @Setter @NoArgsConstructor
public class RequestStatusHistory {

    /**
     * To-Do 목록에서 내려간 시점을 나타내는 이력 표시값. **화면·집계가 이 문자열로 판별한다.**
     * (예전에는 SyncService 안에 있었다 — 기록하는 쪽이 아니라 뜻을 갖는 쪽에 둔다)
     */
    public static final String CLOSED_MARK = "ITSM 할 일 종료";
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    @Column(name = "from_status", length = 20) private String fromStatus;
    @Column(name = "to_status", length = 20)   private String toStatus;
    @Column(name = "from_sta_nm", length = 100) private String fromStaNm;   // ITSM 원본 상태명
    @Column(name = "to_sta_nm", length = 100)   private String toStaNm;
    @Column(name = "from_assignee", length = 100) private String fromAssignee;
    @Column(name = "to_assignee", length = 100)   private String toAssignee;
    @Column(name = "actor_per_id", length = 64)   private String actorPerId;
    @Column(name = "actor_name", length = 100)    private String actorName;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;
}
