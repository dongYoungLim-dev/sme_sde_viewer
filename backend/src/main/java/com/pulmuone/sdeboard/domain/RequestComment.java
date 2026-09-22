package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 요청건 코멘트 — <b>되받을 자리</b>.
 *
 * <p><b>왜 노트를 하나 더 만들지 않았나</b>({@link RequestNote} 는 SME 가 쓰고 나머지가 읽는 단방향이다) —
 * 노트가 둘이면 SME 가 매번 "이건 어디에 쓰지" 를 고르고, 리더에게 쓴 말은 대개 배정된 SDE 도 봐야 하는 말이다.
 * 없는 것은 <i>리더용 노트</i> 가 아니라 <b>되물을 자리</b>였다.
 *
 * <p><b>왜 대화방이 아닌가</b> — 대화방이 커지는 것은 <b>채팅이 요구하는 것들</b>(방·참여자 관리·실시간·
 * 타이핑·푸시) 때문이지 양방향 자체가 아니다. 그 넷을 빼면 남는 것은 이 표 하나다.
 * 방은 곧 요청건이고, 실시간 대신 이미 있는 1분 폴링을 탄다.
 *
 * <p><b>수정·삭제가 없다(append-only).</b> 그래서 초안·이력·동시편집 문제가 애초에 생기지 않는다 —
 * 노트가 그 셋 때문에 표 셋({@code request_note}·{@code request_note_revision}·{@code note_read})을
 * 쓴 것과 대비된다.
 *
 * <p><b>본문은 평문이다.</b> Delta 도 HTML 도 아니다 — 한두 줄 되묻는 자리에 서식이 필요 없고,
 * 서식을 들이면 {@code RequestNoteService.normalizeDelta} 같은 보안 경계가 여기에도 하나 더 생긴다.
 *
 * <p>⚠️ ITSM 에 없는 데이터라 <b>이 보드가 원본</b>이다 — 유실되면 복구할 곳이 없다.
 */
@Entity
@Table(name = "request_comment")
@Getter @Setter @NoArgsConstructor
public class RequestComment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    /**
     * 대화 축. {@code SME_LEAD} / {@code SME_SDE} 두 값뿐이다(사용자 결정 2026-09-10 —
     * <i>"SDE리더와 SME 간에 댓글을 SDE 가 볼 필요가 없고, SDE와 SME 간에 댓글을 SDE 리더가 볼 필요가 없다"</i>).
     *
     * <p>⚠️ <b>사람 쌍이 아니라 역할 쌍이다.</b> 담당 SDE 가 교체되면 후임자가 이전 대화를 본다 —
     * 인수인계가 끊기지 않게 하려는 것이고, 노트 초안이 그 법인 SME 전원에게 보이는 것과 같은 권한선이다.
     */
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    /**
     * 쓴 시점의 역할을 <b>찍어 둔다.</b> 나중에 그 사람의 역할이 바뀌어도(SDE→리더 승격 등)
     * "그때 누가 무슨 자격으로 한 말인가" 가 남는다. 지금 역할로 되짚으면 과거가 조용히 달라진다.
     */
    @Column(name = "author_role", nullable = false, length = 20)
    private String authorRole;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
