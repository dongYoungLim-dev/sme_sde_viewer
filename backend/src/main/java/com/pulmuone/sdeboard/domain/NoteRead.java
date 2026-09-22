package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <b>누가 이 노트를 언제까지 봤나</b> — 목록의 `노트 갱신` 배지가 이 값 하나로 산다.
 *
 * <p><b>왜 필요한가</b> — "갱신됨" 은 <b>보는 사람마다 다른 사실</b>이다. 방금 읽은 SDE 에게는
 * 갱신이 아니고, 사흘 자리를 비운 SDE 에게는 갱신이다. 읽음 시각이 없으면
 * <i>"최근 N시간 안에 공유됐으면 갱신"</i> 같은 근사치밖에 못 쓰는데, 그건 <b>방금 읽은 사람에게도 배지를
 * 띄우고(거짓 양성) 오래 자리를 비운 사람에게는 안 띄운다(거짓 음성).</b> 둘 다 이 기능의 목적을 놓친다.
 *
 * <p>기록 시점은 <b>노트를 실제로 연 순간</b>(GET note)과 <b>자기가 공유한 순간</b>이다.
 * 목록을 스치듯 지나간 것은 읽은 것으로 치지 않는다 — 그러면 아무도 배지를 못 본다.
 *
 * <p>⚠️ ITSM 에 없는 데이터라 <b>이 보드가 원본</b>이다. 다만 유실돼도 손해는 작다 —
 * 최악의 경우 모두가 배지를 한 번 더 볼 뿐이다({@link RequestNote} 와 달리 내용이 아니다).
 */
@Entity
@Table(name = "note_read")
@Getter @Setter @NoArgsConstructor
public class NoteRead {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 이 사람이 마지막으로 이 노트를 본 시각. 공유 시각이 이보다 뒤면 `갱신` 이다. */
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
