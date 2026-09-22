package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * "이 요청이 누구의 ITSM To-Do 로 조회됐나" 매핑.
 * 같은 요청이 리더·담당자 양쪽 목록에 동시에 나올 수 있으므로,
 * 요청 본문(itsm_request)은 1행으로 두고 소유 관계만 여기에 쌓는다.
 */
@Entity
@Table(name = "request_owner")
@Getter @Setter @NoArgsConstructor
public class RequestOwner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    /** 그 사용자의 To-Do 목록에서 사라지면 false */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
