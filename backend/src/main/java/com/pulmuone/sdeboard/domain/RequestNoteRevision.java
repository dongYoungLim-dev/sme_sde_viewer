package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 분석 노트의 <b>공유 이력 한 건</b> — 공유할 때만 한 행씩 쌓인다(append-only).
 *
 * <p><b>왜 필요한가</b> — 공유본을 덮어쓰면 <b>그 내용으로 이미 작업 중이던 SDE 가 혼란스럽다</b>
 * (사용자 지적 2026-09-09). 요구사항이 통째로 바뀌었는데 SDE 는 바뀐 줄조차 몰랐다.
 *
 * <p>⚠️ <b>임시저장은 이력을 남기지 않는다.</b> 공유할 때만 남기므로 요청당 두세 행이다 —
 * 그래서 "이력 표는 무겁다" 는 처음의 반대는 성립하지 않는다. append-only 라
 * 오히려 덮어쓰기로 데이터가 사라질 위험이 준다.
 *
 * <p>⚠️ <b>이력을 남기는 것과 '버전 관리 UI' 는 다르다.</b> 되돌리기·diff 뷰어는 1단계에서 뺐다
 * (사용자 결정 2026-09-09). 화면은 <b>읽기 전용 열람 + 편집기로 불러오기</b>까지다.
 */
@Entity
@Table(name = "request_note_revision")
@Getter @Setter @NoArgsConstructor
public class RequestNoteRevision {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    /** 몇 차 공유인가. 요청 안에서 1부터. 화면이 `n차 공유` 로 부른다. */
    @Column(name = "seq", nullable = false)
    private Integer seq;

    /** 그때 공유한 본문(Quill Delta). {@link RequestNote#getBodyDelta()} 과 같은 형식이다. */
    @Column(name = "body_delta", columnDefinition = "MEDIUMTEXT")
    private String bodyDelta;

    /** 평문 파생값 — 이력 목록의 <b>미리보기</b>가 이 값에서 나온다(Delta 는 목록에 못 쓴다). */
    @Column(name = "body_text", columnDefinition = "MEDIUMTEXT")
    private String bodyText;

    /**
     * <b>무엇을 바꿨는지</b> 한 줄. 2차 공유부터 필수다(사용자 결정 2026-09-09).
     *
     * <p>사람이 쓴 <i>"조회 테이블 TB_A → TB_B"</i> 한 줄이 본문 비교보다 빠르고 정확하다 —
     * 혼란의 정체는 "이전 걸 못 본다" 가 아니라 <b>"바뀐 줄 모른다"</b> 였다.
     * 이력 목록도 이 값이 없으면 시각만 보고 골라야 한다.
     */
    @Column(name = "publish_memo", length = 300)
    private String publishMemo;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
