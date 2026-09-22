package com.pulmuone.sdeboard.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 요청건 코멘트 — 채널별 대화. */
public final class CommentDtos {

    private CommentDtos() {}

    /** 한 줄. `mine` 은 화면이 좌/우를 가르는 데 쓴다(작성자 ID 를 화면이 비교하지 않게). */
    public record CommentView(Long id, String authorName, String authorRole, boolean mine,
                              String body, LocalDateTime createdAt) {}

    /**
     * 채널 한 칸 = 화면의 탭 하나.
     * <p>⚠️ <b>내가 볼 수 있는 채널만 이 목록에 담긴다.</b> 화면에서 감추는 게 아니라 응답에서 뺀다 —
     * 실려 나가면 개발자도구로 그대로 보인다.
     */
    public record ChannelView(String channel, String label, String hint,
                              int unread, List<CommentView> comments) {}

    /**
     * {@code writable=false} 면 화면이 입력칸을 아예 안 그린다.
     * <p>⚠️ <b>왜 화면이 스스로 판단하지 않나</b> — "완료면 못 쓴다" 를 화면이 다시 계산하면
     * 서버와 어긋나는 순간 <b>쓸 수 있는 것처럼 보이다가 등록에서 실패한다.</b>
     * 판단은 한 곳(서버)에서 하고, 그 이유({@code readOnlyReason})까지 같이 내려 화면이 그대로 띄운다.
     */
    public record CommentsResponse(List<ChannelView> channels, boolean writable, String readOnlyReason) {}

    /** 쓰기 — 어느 채널에 남기는지는 화면이 고른다(안 보이는 채널이면 서버가 거절한다). */
    public record CommentWriteRequest(String channel, String body) {}
}
