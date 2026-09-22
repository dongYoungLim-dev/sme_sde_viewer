package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ITSM 요청 미러 (읽기 전용 사본). 동기화가 채운다.
 * 컬럼은 resources/db/schema.sql 과 1:1 (ddl-auto=none).
 *
 * 실제 목록 API(my-to-do-request-operation) 응답으로 확정된 필드:
 *   reqNo · reqTitle · perNm · reqTypCd/reqTypNm · reqDt · defDueDate
 *   · trfPerId · staCd/staNm · reqCompNm · reqCatNm · workNo
 * 목록 응답에 없는 것: 요청 원문(body), 담당자 ID, 담당 리더/SDE 분리 → 상세 API 확인 대기.
 */
@Entity
@Table(name = "itsm_request")
@Getter @Setter @NoArgsConstructor
public class ItsmRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, unique = true, length = 32)
    private String reqNo;

    @Column(name = "title", length = 500)
    private String title;                       // reqTitle

    @Column(name = "requester", length = 200)
    private String requester;                   // perNm — "박지은(park Ji Eun)"

    @Column(name = "req_comp_nm", length = 100)
    private String reqCompNm;                   // 요청 회사

    @Column(name = "req_cat_nm", length = 100)
    private String reqCatNm;                    // 요청 분류

    @Column(name = "comp_cd", length = 10)
    private String compCd;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;                        // 요청 원문 — 상세 API 확정 후

    @Column(name = "req_typ_cd", length = 10)
    private String reqTypCd;
    @Column(name = "req_typ_nm", length = 50)
    private String reqTypNm;                    // 변경 / 배포메인 상태 …

    @Column(name = "trf_per_id", length = 100)
    private String trfPerId;                    // ⚠ ID가 아니라 이름 문자열로 온다

    @Column(name = "leader_per_id", length = 64)
    private String leaderPerId;                 // 목록 미제공
    @Column(name = "leader_name", length = 100)
    private String leaderName;
    @Column(name = "assignee_per_id", length = 64)
    private String assigneePerId;               // 목록 미제공
    @Column(name = "assignee_name", length = 100)
    private String assigneeName;                // trfPerId 값

    @Column(name = "assign_stage", length = 10)
    private String assignStage;                 // INTAKE / LEADER / FINAL

    @Column(name = "itsm_sta_cd", length = 10)
    private String itsmStaCd;                   // 00566 …
    @Column(name = "itsm_sta_nm", length = 100)
    private String itsmStaNm;                   // 변경접수 …

    @Column(name = "work_status", length = 20)
    private String workStatus;                  // 보드 4단계 — {@link BoardStatus} (WAITING/IN_PROGRESS/DONE/UNTRACKED)

    @Column(name = "work_type", length = 20)
    private String workType;                    // processing / direct

    @Column(name = "work_no")
    private Integer workNo;

    @Column(name = "req_dt")
    private LocalDateTime reqDt;
    @Column(name = "due_date")
    private LocalDateTime dueDate;              // defDueDate
    @Column(name = "last_changed_at")
    private LocalDateTime lastChangedAt;

    /**
     * 완료로 **처음 관측한** 시각.
     * ⚠️ ITSM 원본의 완료 시각이 아니다 — 목록 API 에 완료일 필드가 없어서 우리가 만든 값이다.
     * DONE 전이를 본 시점, 또는 To-Do 에서 내려간 시점 중 먼저 온 것. 폴링 주기(5분)와
     * **세션이 살아 있었는지**에 따라 늦어질 수 있다(금요일 저녁 완료 → 월요일 로그인 시 감지되면 월요일).
     * 한 번 채워지면 다시 쓰지 않는다.
     */
    @Column(name = "done_at")
    private LocalDateTime doneAt;

    /** `UNTRACKED → DONE` 을 **사람이 확정**한 경우의 처리자/시각(`app_user.id`). 자동 완료면 둘 다 null. */
    @Column(name = "completed_manually_by")
    private Long completedManuallyBy;
    @Column(name = "completed_manually_at")
    private LocalDateTime completedManuallyAt;

    @Column(name = "raw_json", columnDefinition = "JSON")
    private String rawJson;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 할당 단계 파생 — INTAKE(미할당) / LEADER(할당 요청·담당 미확인) / FINAL(담당 배정됨).
     *
     * ITSM 목록 응답에는 "SME→리더→SDE" 를 구분하는 필드가 없다. 대신 **상태 순번**을 쓴다:
     * SDE 가 요청을 접수해 "공정할당요청"(flow seq 2) 이 되면 그 시점부터 할당이 시작된 것으로 보고,
     * 담당자 이름(trfPerId)이 실려 오면 FINAL(할당 완료 + 담당자 배정)로 올린다.
     *
     * @param flowSeq        현재 상태의 파이프라인 순번 (0 = 파이프라인 밖)
     * @param assignFromSeq  '할당 요청됨'으로 보는 시작 순번 (itsm.assign-from-seq)
     */
    public void recomputeStage(int flowSeq, int assignFromSeq) {
        boolean hasSde = notBlank(assigneePerId) || notBlank(assigneeName);
        boolean hasLeader = notBlank(leaderPerId) || notBlank(leaderName);
        boolean assignStarted = flowSeq > 0 && flowSeq >= assignFromSeq;
        if (hasSde) this.assignStage = "FINAL";
        else if (hasLeader || assignStarted) this.assignStage = "LEADER";
        else this.assignStage = "INTAKE";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * 마지막 소유자까지 ITSM To-Do 에서 사라졌을 때의 다음 상태(2026-09-22, `UR-260922-1`).
     * 예전의 `inferCompletionOnDrop`(서비스요청은 사라지면 완료로 **추정**)을 대체한다 —
     * 스케줄을 잡았던 건은 완료, 안 잡았던 건은 **모른다고 인정**(`UNTRACKED`)하고 사람이 확정한다.
     *
     * @return 상태가 바뀌었으면 true
     */
    public boolean onDroppedFromTodo() {
        if (BoardStatus.IN_PROGRESS.equals(workStatus)) { this.workStatus = BoardStatus.DONE; return true; }
        if (BoardStatus.WAITING.equals(workStatus) || workStatus == null) { this.workStatus = BoardStatus.UNTRACKED; return true; }
        return false;
    }
}
