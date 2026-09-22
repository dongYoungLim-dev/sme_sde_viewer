package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <b>누가 어느 채널을 언제까지 봤나</b> — 목록의 `새 댓글` 배지가 이 값 하나로 산다.
 *
 * <p>{@link NoteRead} 와 같은 이유로 존재한다 — "새 글이 있다" 는 <b>보는 사람마다 다른 사실</b>이라
 * "최근 N시간 안에 쓰였으면 새 글" 같은 근사치로는 방금 읽은 사람에게도 배지가 뜨고(거짓 양성)
 * 오래 자리를 비운 사람에게는 안 뜬다(거짓 음성).
 *
 * <p>⚠️ <b>채널까지 키에 넣는다.</b> 요청 하나에 채널이 둘이고 사람마다 보는 채널이 다르므로,
 * 요청 단위로만 찍으면 <b>한쪽을 열었을 때 다른 쪽 배지까지 같이 꺼진다.</b>
 *
 * <p>⚠️ {@link NoteRead} 를 재사용하지 않고 표를 나눈 이유 — 그쪽은 노트용이고 채널 개념이 없다.
 * 한 표에 두 뜻을 담으면 어느 쪽 읽음인지 알 수 없게 된다.
 *
 * <p>유실돼도 손해는 작다 — 최악의 경우 모두가 배지를 한 번 더 볼 뿐이다(내용이 아니다).
 */
@Entity
@Table(name = "comment_read")
@Getter @Setter @NoArgsConstructor
public class CommentRead {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    /** 이 사람이 이 채널을 마지막으로 본 시각. 이보다 뒤에 쓰인 글이 `새 댓글` 이다. */
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
