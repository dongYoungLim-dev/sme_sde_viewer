package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 동기화 실행 이력. */
@Entity
@Table(name = "sync_log")
@Getter @Setter @NoArgsConstructor
public class SyncLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id") private Long userId;    // 어느 사용자 세션의 동기화인지
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "result", length = 20) private String result;   // SUCCESS / PARTIAL / FAIL
    @Column(name = "fetched_cnt", nullable = false) private int fetchedCnt;
    @Column(name = "upserted_cnt", nullable = false) private int upsertedCnt;
    @Column(name = "changed_cnt", nullable = false) private int changedCnt;
    @Column(name = "error_msg", columnDefinition = "TEXT") private String errorMsg;
}
