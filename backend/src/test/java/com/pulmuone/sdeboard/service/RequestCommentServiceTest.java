package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.CommentRead;
import com.pulmuone.sdeboard.domain.RequestComment;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.CommentReadRepository;
import com.pulmuone.sdeboard.repo.RequestCommentRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.CommentDtos.*;
import com.pulmuone.sdeboard.web.dto.RequestDtos.DetailResponse;
import com.pulmuone.sdeboard.web.dto.RequestDtos.RequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 요청건 코멘트 — <b>SME↔리더 / SME↔SDE 두 축</b>.
 *
 * <p>여기서 지키는 것 중 <b>첫째는 누출 방지</b>다. 사용자 결정(2026-09-10)이
 * <i>"SDE리더와 SME 간에 댓글을 SDE 가 볼 필요가 없고, SDE와 SME 간에 댓글을 SDE 리더가 볼 필요가 없다"</i>
 * 인데, 이건 <b>화면에서 감춰서는 지켜지지 않는다</b> — 응답에 실리면 개발자도구로 그대로 보인다.
 * 그래서 "리더 응답에 SME_SDE 가 없다" 를 테스트로 못 박는다(분석 노트 초안 {@code draftDelta} 와 같은 규칙).
 */
class RequestCommentServiceTest {

    private static final String REQ = "CSD-A";
    private static final long SME_ID = 1, LEAD_ID = 2, SDE_ID = 3;

    private RequestCommentRepository commentRepo;
    private CommentReadRepository readRepo;
    private AppUserRepository userRepo;
    private DashboardService dashboard;
    private RequestCommentService svc;

    /** 저장소 대역 — append-only 라 리스트 하나면 충분하다. */
    private final List<RequestComment> stored = new ArrayList<>();
    private final List<CommentRead> reads = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    private static AppUser user(long id, String role, String name) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setName(name); u.setLoginId("u" + id);
        return u;
    }

    private static UserSession session(long id, String role) {
        UserSession s = new UserSession();
        s.setUserId(id); s.setRole(role); s.setLoginId("u" + id);
        return s;
    }

    @BeforeEach
    void setUp() {
        commentRepo = mock(RequestCommentRepository.class);
        readRepo = mock(CommentReadRepository.class);
        userRepo = mock(AppUserRepository.class);
        dashboard = mock(DashboardService.class);
        svc = new RequestCommentService(commentRepo, readRepo, userRepo, dashboard);

        lenient().when(userRepo.findById(SME_ID)).thenReturn(Optional.of(user(SME_ID, "SME", "이경미")));
        lenient().when(userRepo.findById(LEAD_ID)).thenReturn(Optional.of(user(LEAD_ID, "SDE_LEADER", "심윤범")));
        lenient().when(userRepo.findById(SDE_ID)).thenReturn(Optional.of(user(SDE_ID, "SDE", "임동영")));

        lenient().when(commentRepo.save(any())).thenAnswer(inv -> {
            RequestComment c = inv.getArgument(0);
            if (c.getId() == null) c.setId(seq.getAndIncrement());
            stored.add(c);
            return c;
        });
        lenient().when(commentRepo.findByReqNoAndChannelOrderByCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(inv -> stored.stream()
                        .filter(c -> c.getReqNo().equals(inv.getArgument(0)))
                        .filter(c -> c.getChannel().equals(inv.getArgument(1)))
                        .sorted(Comparator.comparing(RequestComment::getCreatedAt))
                        .toList());
        lenient().when(readRepo.save(any())).thenAnswer(inv -> {
            CommentRead r = inv.getArgument(0);
            reads.removeIf(x -> x.getReqNo().equals(r.getReqNo())
                    && x.getUserId().equals(r.getUserId()) && x.getChannel().equals(r.getChannel()));
            reads.add(r);
            return r;
        });
        // 기본은 진행 중인 건이다. `완료`(DONE)로 바꾸는 테스트만 아래 done() 을 부른다.
        // ⚠️ **먼저 만들고 나서 스텁한다.** `when(...)` 의 인자 안에서 또 `when(...)` 을 부르면
        //    Mockito 가 앞 스터빙이 안 끝난 것으로 보고 UnfinishedStubbing 을 던진다.
        DetailResponse inProgress = detail("IN_PROGRESS");
        lenient().when(dashboard.detail(any(), eq(REQ))).thenReturn(inProgress);
        lenient().when(readRepo.findByReqNoAndUserIdAndChannel(anyString(), any(), anyString()))
                .thenAnswer(inv -> reads.stream()
                        .filter(r -> r.getReqNo().equals(inv.getArgument(0)))
                        .filter(r -> r.getUserId().equals(inv.getArgument(1)))
                        .filter(r -> r.getChannel().equals(inv.getArgument(2)))
                        .findFirst());
    }

    /** 코멘트 서비스가 상세에서 보는 것은 `workStatus` 하나다(범위 판정 + 완료 여부). */
    private static DetailResponse detail(String workStatus) {
        RequestView v = mock(RequestView.class);
        lenient().when(v.workStatus()).thenReturn(workStatus);
        return new DetailResponse(v, List.of(), List.of(), List.of(), List.of());
    }

    private void done() {
        DetailResponse d = detail("DONE");      // 스텁 밖에서 먼저 만든다(위 UnfinishedStubbing 주석 참고)
        when(dashboard.detail(any(), eq(REQ))).thenReturn(d);
    }

    private void writeAs(long userId, String role, String channel, String body) {
        svc.write(session(userId, role), REQ, new CommentWriteRequest(channel, body));
    }

    private List<String> channelsSeenBy(long userId, String role) {
        return svc.list(session(userId, role), REQ).channels().stream().map(ChannelView::channel).toList();
    }

    // ── 누출 방지 (이 기능의 존재 이유)

    @Test
    void SME_는_두_축을_모두_본다() {
        // SME 는 두 대화의 **한쪽 끝**이다 — 리더에게 할 말과 담당 SDE 에게 할 말을 스스로 고른다.
        assertThat(channelsSeenBy(SME_ID, "SME"))
                .containsExactly(RequestCommentService.SME_LEAD, RequestCommentService.SME_SDE);
    }

    @Test
    void 리더_응답에는_SME와_SDE의_대화가_실리지_않는다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "이건 담당자에게만 하는 말");

        CommentsResponse res = svc.list(session(LEAD_ID, "SDE_LEADER"), REQ);

        assertThat(res.channels()).extracting(ChannelView::channel)
                .containsExactly(RequestCommentService.SME_LEAD);
        // 본문이 어디에도 실려 나가지 않는다 — 화면에서 감추는 방식이면 여기서 새어 나간다
        assertThat(res.channels()).flatExtracting(ChannelView::comments).isEmpty();
    }

    @Test
    void SDE_응답에는_SME와_리더의_대화가_실리지_않는다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_LEAD, "이 건은 A 아는 사람으로 부탁합니다");

        CommentsResponse res = svc.list(session(SDE_ID, "SDE"), REQ);

        assertThat(res.channels()).extracting(ChannelView::channel)
                .containsExactly(RequestCommentService.SME_SDE);
        assertThat(res.channels()).flatExtracting(ChannelView::comments).isEmpty();
    }

    @Test
    void 안_보이는_채널에는_글도_남길_수_없다() {
        // 읽기만 막으면 **남의 대화에 글을 심을 수 있다.** 쓰기도 같은 관문을 지나야 한다.
        assertThatThrownBy(() -> writeAs(SDE_ID, "SDE", RequestCommentService.SME_LEAD, "끼어들기"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stored).isEmpty();
    }

    @Test
    void 조회_범위_밖이면_존재_자체를_알리지_않는다() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(dashboard).detail(any(), eq(REQ));

        assertThatThrownBy(() -> svc.list(session(SDE_ID, "SDE"), REQ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── 새 글 배지

    @Test
    void 내가_쓴_글은_새_글로_세지_않는다() {
        // 방금 쓴 내 글에 배지가 붙으면 사람이 배지를 안 믿게 된다.
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "확인 부탁합니다");

        assertThat(svc.list(session(SME_ID, "SME"), REQ).channels())
                .allSatisfy(c -> assertThat(c.unread()).isZero());
    }

    @Test
    void 상대가_쓴_글은_열기_전까지_새_글이다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "확인 부탁합니다");

        CommentsResponse before = svc.list(session(SDE_ID, "SDE"), REQ);
        assertThat(before.channels()).singleElement()
                .satisfies(c -> assertThat(c.unread()).isEqualTo(1));

        svc.markRead(session(SDE_ID, "SDE"), REQ, RequestCommentService.SME_SDE);

        assertThat(svc.list(session(SDE_ID, "SDE"), REQ).channels()).singleElement()
                .satisfies(c -> assertThat(c.unread()).isZero());
    }

    @Test
    void 조회만으로는_읽음이_찍히지_않는다() {
        // ⚠️ 두 채널이 한 화면에 있어도 사람이 보는 것은 열어 둔 탭 하나뿐이다.
        //    조회에서 찍으면 **안 본 쪽 배지까지 같이 꺼진다.**
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "첫 줄");

        svc.list(session(SDE_ID, "SDE"), REQ);
        svc.list(session(SDE_ID, "SDE"), REQ);

        assertThat(svc.list(session(SDE_ID, "SDE"), REQ).channels()).singleElement()
                .satisfies(c -> assertThat(c.unread()).isEqualTo(1));
    }

    @Test
    void 읽은_뒤에_온_글만_다시_새_글이_된다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "첫 줄");
        svc.markRead(session(SDE_ID, "SDE"), REQ, RequestCommentService.SME_SDE);
        // ⚠️ 벽시계에 기대지 않는다 — `AppTime.now()` 는 **초 단위**라 테스트 한 판이 같은 초에 끝난다.
        //    시각을 명시해야 "읽기 전/후" 라는 이 테스트의 뜻이 실제로 검사된다.
        LocalDateTime readAt = reads.get(0).getReadAt();
        stored.get(0).setCreatedAt(readAt.minusHours(2));       // 읽기 전에 온 글
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "둘째 줄");
        stored.get(1).setCreatedAt(readAt.plusSeconds(1));      // 읽은 뒤에 온 글

        assertThat(svc.list(session(SDE_ID, "SDE"), REQ).channels()).singleElement()
                .satisfies(c -> assertThat(c.unread()).isEqualTo(1));
    }

    // ── 완료 건 (사용자 결정 2026-09-11 — "댓글 확인만 가능하고 더이상 추가 할 수 없도록")

    @Test
    void 완료된_건에는_새_코멘트를_남길_수_없다() {
        // 끝난 일에 말을 더하면 아무도 안 읽는 곳에 남는다 — 담당자는 이미 다음 건으로 갔다.
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "완료 전에 남긴 줄");
        done();

        assertThatThrownBy(() -> writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "완료 뒤에 남기려는 줄"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(stored).hasSize(1);        // 한 줄도 늘지 않았다
    }

    @Test
    void 완료된_건이어도_지난_코멘트는_읽을_수_있다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "완료 전에 남긴 줄");
        done();

        CommentsResponse res = svc.list(session(SDE_ID, "SDE"), REQ);

        assertThat(res.channels()).singleElement()
                .satisfies(c -> assertThat(c.comments()).hasSize(1));
        // 화면이 다시 계산하지 않게 **서버가** 못 쓴다는 것과 그 이유를 함께 내려 준다
        assertThat(res.writable()).isFalse();
        assertThat(res.readOnlyReason()).isEqualTo(RequestCommentService.DONE_REASON);
    }

    @Test
    void 완료된_건도_읽음은_찍힌다() {
        // 못 쓰는 것이지 못 읽는 것이 아니다. 안 찍으면 완료 건의 `새 댓글` 배지가 영원히 안 꺼진다.
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "확인 부탁합니다");
        done();

        svc.markRead(session(SDE_ID, "SDE"), REQ, RequestCommentService.SME_SDE);

        assertThat(svc.list(session(SDE_ID, "SDE"), REQ).channels()).singleElement()
                .satisfies(c -> assertThat(c.unread()).isZero());
    }

    @Test
    void 진행_중인_건은_쓸_수_있다고_알려_준다() {
        assertThat(svc.list(session(SME_ID, "SME"), REQ).writable()).isTrue();
        assertThat(svc.list(session(SME_ID, "SME"), REQ).readOnlyReason()).isNull();
    }

    // ── 본문

    @Test
    void 빈_글은_거절한다() {
        assertThatThrownBy(() -> writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 상한을_넘으면_DB_가_아니라_여기서_막는다() {
        // 상한이 없으면 컬럼(2000)이 대신 잘라 낸다 — 쓴 사람은 잘린 줄도 모른다.
        String tooLong = "가".repeat(RequestCommentService.MAX_BODY_CHARS + 1);
        assertThatThrownBy(() -> writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void 쓴_시점의_역할이_남는다() {
        // 나중에 SDE 가 리더로 승격해도 "그때 누가 무슨 자격으로 한 말인가" 가 달라지면 안 된다.
        writeAs(SDE_ID, "SDE", RequestCommentService.SME_SDE, "이 부분이 무슨 뜻입니까");

        assertThat(stored).singleElement().satisfies(c -> {
            assertThat(c.getAuthorRole()).isEqualTo("SDE");
            assertThat(c.getBody()).isEqualTo("이 부분이 무슨 뜻입니까");
        });
    }

    @Test
    void 앞뒤_공백은_지우고_저장한다() {
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "  확인 부탁합니다\n\n");
        assertThat(stored).singleElement()
                .satisfies(c -> assertThat(c.getBody()).isEqualTo("확인 부탁합니다"));
    }

    @Test
    void 내_글인지_서버가_알려준다() {
        // 화면이 작성자 ID 를 비교하지 않게 한다 — 좌/우 정렬 하나 때문에 ID 를 내보낼 이유가 없다.
        writeAs(SME_ID, "SME", RequestCommentService.SME_SDE, "SME 가 쓴 줄");

        assertThat(svc.list(session(SDE_ID, "SDE"), REQ).channels()).singleElement()
                .satisfies(c -> assertThat(c.comments()).singleElement()
                        .satisfies(v -> {
                            assertThat(v.mine()).isFalse();
                            assertThat(v.authorName()).isEqualTo("이경미");
                        }));
    }

    @Test
    void 역할이_없으면_코멘트_자체를_못_연다() {
        when(userRepo.findById(9L)).thenReturn(Optional.of(user(9, "GUEST", "손님")));

        assertThatThrownBy(() -> svc.list(session(9, "GUEST"), REQ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
