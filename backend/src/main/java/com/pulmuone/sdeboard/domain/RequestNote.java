package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 요청 분석 노트 — SME 가 적는 **요구사항 정의서**, 할당받은 SDE 가 읽는다.
 *
 * <p>현업이 주는 것은 텍스트 몇 줄·PPT 몇 장이라 되묻는 과정이 반드시 생긴다.
 * SME 가 먼저 분석해 적어 두면 SDE 가 그걸 읽고 시작할 수 있다.
 *
 * <p>⚠️ ITSM 에 없는 데이터라 <b>이 보드가 원본</b>이다({@link SdeAssignment} 과 같은 성격).
 * 유실되면 복구할 곳이 없다.
 *
 * <p><b>왜 HTML 이 아니라 Delta 인가</b> — 리치 에디터의 HTML 을 저장하면 그리는 순간
 * 주입 경로가 생기고, 서버 HTML 새니타이저가 필요해진다. Quill 의 구조화 포맷(Delta, JSON)을 담으면
 * 마크업이 아예 오가지 않는다. 저장 전에 {@code RequestNoteService} 가 ops 를 다시 조립하므로
 * 알 수 없는 속성은 남지 않는다.
 */
@Entity
@Table(name = "request_note")
@Getter @Setter @NoArgsConstructor
public class RequestNote {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, unique = true, length = 32)
    private String reqNo;

    /**
     * Quill Delta(JSON). <b>현재 공유본</b> — SDE·리더가 보는 유일한 값이다.
     * 최신 {@link RequestNoteRevision} 의 사본이며(의도한 중복), 공유 트랜잭션에서 함께 쓴다.
     * 이렇게 두면 읽는 코드({@code notedReqNos} 배지 · {@code toDto})를 한 줄도 안 고친다.
     */
    @Column(name = "body_delta", columnDefinition = "MEDIUMTEXT")
    private String bodyDelta;

    /** 평문 파생값 — 검색·목록 미리보기·내보내기용. Delta 에서 서버가 만든다. */
    @Column(name = "body_text", columnDefinition = "MEDIUMTEXT")
    private String bodyText;

    /**
     * 아직 공유하지 않은 <b>작업본</b>. 있으면 SME 화면은 편집 상태를 유지한다(`UR-260909-6`).
     *
     * <p>⚠️ <b>본문을 두 벌로 두는 것이 이 기능의 핵심이다.</b> 한 행에 본문이 하나뿐이면
     * "고치는 중" 과 "공유 중" 이 같은 칸을 두고 다투어, <b>공유한 노트를 고치다 임시저장하는 순간
     * SDE 가 보던 내용이 사라진다.</b>
     *
     * <p>⚠️ 이 값은 <b>편집 권한이 있는 사람에게만</b> 응답에 실린다(그 법인 SME).
     * 화면에서 감추는 게 아니라 응답에서 뺀다 — 개발자도구로 보이면 숨긴 의미가 없다.
     */
    @Column(name = "draft_delta", columnDefinition = "MEDIUMTEXT")
    private String draftDelta;

    @Column(name = "draft_updated_at")
    private LocalDateTime draftUpdatedAt;

    /**
     * 마지막으로 <b>공유</b>한 시각. 읽는 쪽에 보여줄 시각은 이것이다 —
     * {@code updatedAt} 을 보여주면 SME 가 초안을 저장할 때마다 SDE 화면의 시각이 바뀐다(내용은 그대로인데).
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    /**
     * 낙관적 잠금 키 — 저장 요청이 들고 온 값과 다르면 그 사이 누가 고친 것이다.
     * ⚠️ <b>초안 저장도 이 값을 올린다.</b> 키를 둘로 나누면 "초안 기준 최신인데 공유본 기준으론 아니다"
     * 라는 설명 못 할 상태가 생긴다. 읽는 쪽에 보여줄 시각은 {@code publishedAt} 이 따로 맡는다.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
