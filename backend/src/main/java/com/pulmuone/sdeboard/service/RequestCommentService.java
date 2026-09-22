package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppTime;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.CommentRead;
import com.pulmuone.sdeboard.domain.RequestComment;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.CommentReadRepository;
import com.pulmuone.sdeboard.repo.RequestCommentRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.CommentDtos.*;
import com.pulmuone.sdeboard.web.dto.RequestDtos.DetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 요청건 코멘트 — <b>SME ↔ 리더</b>, <b>SME ↔ SDE</b> 두 축의 대화.
 *
 * <p><b>이 클래스가 지키는 것은 하나다 — 안 보이는 채널은 응답에 싣지 않는다.</b>
 * 화면에서 감추는 방식이면 개발자도구로 그대로 보인다(분석 노트 초안 {@code draftDelta} 와 같은 규칙).
 * 그래서 목록도 쓰기도 전부 {@link #visibleChannels} 하나를 지나간다.
 *
 * <p><b>채널을 무엇으로 판정하나</b> — 사용자 분석 때는 {@code request_owner ∩ role} 로 잡자고 했는데,
 * 실제로는 <b>조회 범위가 이미 그 일을 하고 있다.</b> 리더의 범위는 팀 전체 소유자, SDE 의 범위는 자기 것뿐이다.
 * 그래서 <b>범위 통과 + 역할</b> 두 가지면 충분하고, ITSM 이 이름 문자열로 주는
 * {@code assigneeName}(동명이인·표기 차이로 깨진다)을 건드릴 이유가 없어졌다.
 *
 * <p>⚠️ <b>1초짜리 사각지대가 있다.</b> {@link com.pulmuone.sdeboard.domain.AppTime#now()} 가 초 단위로 자르므로
 * <b>내가 탭을 연 것과 같은 초에 쓰인 글</b>은 읽음보다 먼저인지 나중인지 구분할 수 없다.
 * 여기서는 {@code isAfter} 를 써서 <b>읽은 것으로 친다</b> — {@code note_read} 배지와 같은 규칙이다.
 * 반대로 하면(같은 초를 안 본 것으로) <b>탭을 열어도 배지가 안 꺼지는</b> 경우가 생기는데,
 * 그건 배지를 아예 못 믿게 만든다. 놓친 글도 <b>다음에 탭을 열면 스레드에 그대로 있다</b> —
 * 사라지는 것은 배지뿐이고, 이 1초는 사람이 같은 초에 읽고 쓰는 경우에만 겹친다.
 *
 * <p>⚠️ <b>알려진 구멍 하나</b> — 리더가 <b>자기가 담당자로 일하고 있는</b> 건에서도
 * {@code SME_SDE} 축은 못 본다(역할이 리더라서). 실측상 그런 건이 있다(심윤범 활성 21건).
 * 다만 <b>조용히 실패하지는 않는다</b> — SME 화면에는 탭 이름이 `SDE 리더` · `담당 SDE` 로 둘 다 보이므로
 * 받는 사람을 사람이 고른다. 채널을 사람 쌍으로 바꾸는 것은 사용자가 기각한 선택지다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestCommentService {

    /** SME ↔ SDE 리더. 담당 SDE 에게는 안 보인다. */
    public static final String SME_LEAD = "SME_LEAD";
    /** SME ↔ 담당 SDE. 리더에게는 안 보인다. */
    public static final String SME_SDE = "SME_SDE";

    /** 한 줄 상한. 되묻는 자리라 길 이유가 없고, 상한이 없으면 DB 가 대신 잘라 낸다(컬럼 2000). */
    static final int MAX_BODY_CHARS = 2000;

    private final RequestCommentRepository commentRepo;
    private final CommentReadRepository readRepo;
    private final AppUserRepository userRepo;
    private final DashboardService dashboard;

    /**
     * 내가 볼 수 있는 채널과 그 대화.
     * <p><b>여기서는 읽음을 찍지 않는다</b> — 두 채널이 한 화면에 있어도 사람이 보는 것은 열어 둔 탭 하나뿐이다.
     * 여기서 둘 다 찍으면 <b>안 본 쪽 배지까지 같이 꺼진다.</b> 읽음은 {@link #markRead} 가 탭 단위로 찍는다.
     */
    public CommentsResponse list(UserSession session, String reqNo) {
        AppUser me = requireUser(session);
        DetailResponse detail = requireInScope(session, reqNo);
        Set<String> channels = channelsOf(me);
        boolean done = isDone(detail);

        Map<Long, String> names = new HashMap<>();
        List<ChannelView> out = new ArrayList<>();
        for (String ch : channels) {
            List<RequestComment> rows = commentRepo.findByReqNoAndChannelOrderByCreatedAtAsc(reqNo, ch);
            LocalDateTime readAt = readRepo.findByReqNoAndUserIdAndChannel(reqNo, me.getId(), ch)
                    .map(CommentRead::getReadAt).orElse(null);
            // 내가 쓴 글은 '새 글' 이 아니다 — 방금 쓴 내 글에 배지가 붙으면 배지를 안 믿게 된다
            int unread = (int) rows.stream()
                    .filter(c -> !c.getAuthorId().equals(me.getId()))
                    .filter(c -> readAt == null || c.getCreatedAt().isAfter(readAt))
                    .count();
            List<CommentView> views = rows.stream()
                    .map(c -> new CommentView(c.getId(), nameOf(c.getAuthorId(), names), c.getAuthorRole(),
                            c.getAuthorId().equals(me.getId()), c.getBody(), c.getCreatedAt()))
                    .toList();
            out.add(new ChannelView(ch, labelOf(ch), hintOf(ch), unread, views));
        }
        return new CommentsResponse(out, !done, done ? DONE_REASON : null);
    }

    /** 한 줄 남긴다. 수정·삭제는 없다 — append-only 라 초안·이력·동시편집 문제가 생기지 않는다. */
    @Transactional
    public CommentsResponse write(UserSession session, String reqNo, CommentWriteRequest req) {
        AppUser me = requireUser(session);
        DetailResponse detail = requireInScope(session, reqNo);
        // ⚠️ 완료된 건은 **읽기만** 된다(사용자 결정 2026-09-11). 끝난 일에 말을 더하면
        //    아무도 안 읽는 곳에 남는다 — 담당자는 이미 다음 건으로 갔고 목록에서도 내려갔다.
        if (isDone(detail))
            throw new ResponseStatusException(HttpStatus.CONFLICT, DONE_REASON);
        Set<String> channels = channelsOf(me);
        String ch = req == null ? null : req.channel();
        // 안 보이는 채널에 쓰는 것도 막는다 — 읽기만 막으면 남의 대화에 글을 심을 수 있다
        if (ch == null || !channels.contains(ch))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 대화에는 글을 남길 수 없습니다.");

        String body = req.body() == null ? "" : req.body().strip();
        if (body.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용을 입력하세요.");
        if (body.length() > MAX_BODY_CHARS)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "코멘트가 너무 깁니다 (최대 " + MAX_BODY_CHARS + "자).");

        RequestComment c = new RequestComment();
        c.setReqNo(reqNo);
        c.setChannel(ch);
        c.setAuthorId(me.getId());
        c.setAuthorRole(me.getRole());        // 지금 역할을 찍어 둔다 — 나중에 바뀌어도 과거가 안 달라진다
        c.setBody(body);
        c.setCreatedAt(AppTime.now());
        commentRepo.save(c);

        markRead(session, reqNo, ch);         // 내가 쓴 순간은 그 채널을 본 순간이기도 하다
        return list(session, reqNo);
    }

    /** 탭을 연 순간이 곧 '읽음' 이다. 목록을 스쳐 지나간 것은 읽은 것으로 치지 않는다. */
    @Transactional
    public void markRead(UserSession session, String reqNo, String channel) {
        AppUser me = requireUser(session);
        requireInScope(session, reqNo);
        // 완료 건이어도 **읽음은 찍는다** — 못 쓰는 것이지 못 읽는 것이 아니고,
        // 안 찍으면 완료 건의 `새 댓글` 배지가 영원히 안 꺼진다.
        if (!channelsOf(me).contains(channel))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 대화를 볼 수 없습니다.");
        CommentRead r = readRepo.findByReqNoAndUserIdAndChannel(reqNo, me.getId(), channel)
                .orElseGet(() -> {
                    CommentRead n = new CommentRead();
                    n.setReqNo(reqNo); n.setUserId(me.getId()); n.setChannel(channel);
                    return n;
                });
        r.setReadAt(AppTime.now());
        readRepo.save(r);
    }

    // ── 권한

    /** 완료 건에 뜨는 문구. 서버가 판단하고 <b>이유까지</b> 내려 준다 — 화면이 다시 계산하지 않는다. */
    static final String DONE_REASON = "완료된 요청입니다. 지난 코멘트는 볼 수 있지만 새로 남길 수는 없습니다.";

    /** 조회 범위 밖이면 존재 자체를 알리지 않는다(404) — 상세 조회와 같은 규칙을 그대로 탄다. */
    private DetailResponse requireInScope(UserSession session, String reqNo) {
        return dashboard.detail(session, reqNo);
    }

    /**
     * ⚠️ <b>완료 판정은 {@code workStatus == DONE} 하나다.</b> `할 일 종료`(누구의 To-Do 에도 없음)와
     * 섞지 않는다 — 그쪽은 <b>끝났다는 뜻이 아니라 추적이 끊겼다는 뜻</b>이라, 오히려 되물어야 하는 상태다.
     */
    private static boolean isDone(DetailResponse d) {
        return d != null && d.request() != null && "DONE".equals(d.request().workStatus());
    }

    /**
     * 내가 볼 수 있는 채널 — <b>역할뿐이다.</b>
     * <p>범위는 {@link #requireInScope} 가 이미 걸렀다: 리더의 범위는 팀 전체 소유자,
     * SDE 의 범위는 자기 것뿐이라 <b>범위 통과 + 역할</b>이면 채널이 정해진다.
     */
    private Set<String> channelsOf(AppUser me) {
        Set<String> ch = channelsForRole(me.getRole());
        if (ch.isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "코멘트를 쓸 수 있는 역할이 아닙니다.");
        return ch;
    }

    /**
     * 역할 → 채널. <b>이 표가 곧 사용자 결정(2026-09-10)이다.</b>
     * ⚠️ 목록 배지도 같은 표를 봐야 한다 — {@code DashboardService.commentBadges} 가 이걸 부른다.
     * 한쪽만 고치면 <b>배지는 뜨는데 열면 없는</b>(또는 그 반대) 상태가 된다.
     */
    public static Set<String> channelsForRole(String role) {
        if ("SME".equals(role)) return new LinkedHashSet<>(List.of(SME_LEAD, SME_SDE));
        if ("SDE_LEADER".equals(role)) return new LinkedHashSet<>(List.of(SME_LEAD));
        if ("SDE".equals(role)) return new LinkedHashSet<>(List.of(SME_SDE));
        return Set.of();
    }

    private AppUser requireUser(UserSession session) {
        return userRepo.findById(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private String nameOf(Long userId, Map<Long, String> cache) {
        return cache.computeIfAbsent(userId,
                id -> userRepo.findById(id).map(AppUser::getName).orElse("(탈퇴)"));
    }

    /** 탭 이름은 **상대방**이다 — 내가 누구인지는 이미 알고 있다. */
    private static String labelOf(String ch) {
        return SME_LEAD.equals(ch) ? "SME ↔ SDE 리더" : "SME ↔ 담당 SDE";
    }

    private static String hintOf(String ch) {
        return SME_LEAD.equals(ch)
                ? "담당 SDE 에게는 보이지 않습니다."
                : "SDE 리더에게는 보이지 않습니다.";
    }
}
