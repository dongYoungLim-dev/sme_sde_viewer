package com.pulmuone.sdeboard.web.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 요청(To-Do 미러) 관련 API 메시지.
 *
 * <p>이름 규칙 — **들어오는 것은 `~Request`, 응답은 `~Response`, 응답 안의 조각은 `~View`.**
 * 예전에는 `~Dto`·`~Req`·`~Res` 세 벌이 한 파일(`Dtos.java`, record 29개)에 섞여 있어서,
 * 새 메시지를 만들 때마다 어느 접미사를 쓸지 추측해야 했다.
 */
public final class RequestDtos {

    private RequestDtos() {}

    /**
     * 대시보드 요청 행.
     * itsmStaCd/itsmStaNm 은 ITSM 원본 상태(예: 00566 / "변경접수") — 참고용이다.
     * workStatus 는 **이 보드의 4단계 상태**(WAITING/IN_PROGRESS/DONE/UNTRACKED)이고 화면이 보여주는 상태다
     * (2026-09-22, 11단계 파이프라인 폐기 — `UR-260922-1`).
     */
    public record RequestView(
            String reqNo, String title, String requester, String dept, String requesterName,
            String body, String compCd, String reqCompNm, String reqCatNm,
            String reqTypCd, String reqTypNm,
            String leaderPerId, String leaderName, String assigneePerId, String assigneeName,
            String assignStage, String workStatus, String itsmStaCd, String itsmStaNm,
            String workType, Integer workNo,
            LocalDate reqDt, LocalDate dueDate, int attachmentCount,
            boolean late, boolean soon,
            boolean inTodo, LocalDateTime closedAt,
            String reqType, String targetSystem, String priority,
            /** ⚠️ ITSM 원본의 완료 시각이 아니라 **우리가 완료를 처음 관측한 시각**. 화면에 그렇게 밝힌다. */
            LocalDateTime doneAt,
            /**
             * 이 건을 지금 들고 있는 SDE 의 인력풀 담당 차수(그 요청 법인 기준). 모르면 null.
             * ⚠️ 숫자가 아니라 <b>문자열</b>이다 — 한 법인에서 시스템마다 차수가 달라(FNC 인사 1차 · 하루 3차)
             * 어느 쪽인지 ITSM 이 알려주지 않으므로 고르지 않고 이어 붙인다("1·3").
             */
            String assigneeTier,
            /** SME 분석 노트가 작성돼 있는가 — 목록 배지용. 없으면 SDE 가 매번 열어 봐야 안다. */
            boolean hasNote,
            /**
             * **내가 마지막으로 본 뒤에 다시 공유됐는가** — 목록의 `노트 갱신` 배지.
             * ⚠️ 보는 사람마다 다른 값이다(`note_read` 기준). 한 번도 안 본 노트는 `갱신` 이 아니라 그냥 `노트` 다.
             */
            boolean noteUpdated,
            /**
             * **내가 아직 안 본 코멘트 수** — 목록의 `새 댓글` 배지.
             * ⚠️ 보는 사람마다 다른 값이다. 내가 볼 수 있는 채널만 세고, 내가 쓴 글은 안 센다.
             */
            int unreadComments,
            /** 이 건이 **나(지금 보는 사람)의 To-Do 에 처음 나타난** 시각(2026-09-14 부터 — `UR-260911-2`). */
            LocalDateTime firstSeen,
            /** 위 시각이 `app.new-request-hours` 안이면 true — 대시보드가 '신규 유입'으로 묶는다. */
            boolean fresh,
            /** 처리 일정 요약. 한 번도 일정을 잡은 적이 없으면 null. 변경(수정·취소) 이력도 여기서 나간다. */
            ScheduleInfo schedule,
            /** SME 가 올린 첨부 수 — 목록 배지용. */
            int fileCount,
            /** `UNTRACKED → DONE` 을 사람이 확정한 건인가 — 화면이 "수동 확정" 표시를 붙인다. */
            boolean completedManually) {}

    /**
     * 목록 한 줄에 붙는 일정 요약.
     * `start`·`end`·`assignee*` 는 **지금 유효한(ACTIVE) 일정**이고, 없으면(취소돼 대기로 돌아왔다면) null 이다.
     * `changes` 는 수정·취소된 횟수, `lastReason`·`lastKind`·`lastChangedAt` 은 **가장 최근 변경**이다
     * (사용자 결정 §8-8 — 대시보드·요청목록·스케줄관리 세 화면에서 변경된 건을 알아볼 수 있어야 한다).
     */
    public record ScheduleInfo(Long id, LocalDateTime start, LocalDateTime end,
                               Long assigneeId, String assigneeName,
                               int changes, String lastKind, String lastReason, LocalDateTime lastChangedAt) {}

    public record HistoryView(String fromStatus, String toStatus,
                              String fromStaNm, String toStaNm,
                              String fromAssignee, String toAssignee,
                              String actorPerId, String actorName, LocalDateTime observedAt) {}

    public record AttachmentView(Long id, String fileName, Long fileSize, String contentType) {}

    /** `attachments` = ITSM 이 가진 첨부(아직 비어 있다), `files` = SME 가 이 보드에 올린 첨부. 화면에서 출처를 구분한다. */
    public record DetailResponse(RequestView request, List<HistoryView> history,
                                 List<AttachmentView> attachments,
                                 List<ScheduleDtos.ScheduleView> schedules, List<FileDtos.FileView> files) {}

    public record TimelineView(String reqNo, String title, String actorName,
                               String fromStatus, String toStatus, String toStaNm,
                               LocalDateTime observedAt) {}

    // ── 작업 완료 목록
    /** 완료일 기준 집계 한 줄용. label = 법인명 또는 담당자명. */
    public record CountView(String label, long count) {}

    /**
     * 완료 목록 응답. `from`/`to` 는 **완료일(= 우리 관측 시각)** 기준 경계다.
     * `note` 는 화면 상단에 그대로 띄워, 이 숫자가 ITSM 원본 완료일이 아님을 읽는 사람이 알게 한다.
     */
    public record DoneListResponse(List<RequestView> rows, int total,
                                   List<CountView> byCorp, List<CountView> byAssignee,
                                   LocalDate from, LocalDate to,
                                   LocalDateTime earliestKnown, String note) {}

    // ── 요청 분석 노트 (요구사항 정의서)
    /**
     * `bodyDelta` 는 **Quill Delta(JSON) 문자열**이다 — HTML 이 아니다(주입 경로를 만들지 않으려고).
     * `bodyText` 는 검색·미리보기용 평문 파생값. `editable` 은 이 사용자가 쓸 수 있는가(그 법인 SME).
     * `updatedAt` 은 저장 시 그대로 되돌려 보내는 **낙관적 잠금 키**다.
     *
     * <p>2026-09-09(`UR-260909-6`) 부터 본문이 두 벌이다.
     * `bodyDelta` = **공유본**(읽는 사람이 보는 유일한 값) · `draftDelta` = 아직 공유 안 한 작업본.
     * ⚠️ **`draftDelta` 는 `editable=true` 인 사람에게만 채워 보낸다** — 화면에서 감추는 게 아니라
     * 응답에서 뺀다. 개발자도구로 보이면 초안을 숨긴 의미가 없다.
     *
     * <p>`state` 는 화면이 그대로 쓰는 상태다 — `EMPTY`(아직 없음) · `READ`(공유본만) · `EDIT`(초안 있음).
     * `publishedAt` 은 **읽는 쪽에 보여줄 시각**이고(초안 저장으로 바뀌지 않는다),
     * `revisionCount` 가 0 보다 크면 화면에 **[공유 이력]** 버튼이 뜬다.
     */
    public record NoteResponse(String reqNo, String bodyDelta, String bodyText,
                               Long authorId, String authorName, LocalDateTime updatedAt,
                               boolean editable, boolean hasContent,
                               String draftDelta, LocalDateTime draftUpdatedAt,
                               LocalDateTime publishedAt, String state, int revisionCount) {}

    /**
     * 노트 저장. `mode` = `DRAFT`(임시저장) 또는 `PUBLISH`(공유).
     * `publishMemo` 는 **무엇을 바꿨는지 한 줄** — 2차 공유부터 필수다(사용자 결정 2026-09-09).
     */
    public record NoteSaveRequest(String bodyDelta, LocalDateTime expectedUpdatedAt,
                                  String mode, String publishMemo) {}

    /**
     * 공유 이력 한 줄 — 팝업 **왼쪽 목록**. 본문(Delta)은 담지 않는다.
     * `preview` 는 `body_text` 앞부분이다 — Delta(JSON)는 목록에 그대로 못 쓴다.
     * `current=true` 가 지금 SDE 가 보고 있는 공유본이다(목록에 `현재` 배지).
     */
    public record NoteRevisionView(int seq, LocalDateTime publishedAt, String authorName,
                                   String publishMemo, String preview, boolean current) {}

    /** 공유 이력 한 건의 본문 — 팝업 **오른쪽**. 읽기 전용으로만 쓰인다. */
    public record NoteRevisionDetailView(int seq, LocalDateTime publishedAt, String authorName,
                                         String publishMemo, String bodyDelta, boolean current) {}
}
