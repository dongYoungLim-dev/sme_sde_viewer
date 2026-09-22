package com.pulmuone.sdeboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestNote;
import com.pulmuone.sdeboard.domain.RequestNoteRevision;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.NoteReadRepository;
import com.pulmuone.sdeboard.repo.RequestNoteRepository;
import com.pulmuone.sdeboard.repo.RequestNoteRevisionRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.RequestDtos.NoteSaveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 임시저장 · 공유 이력의 **흐름**을 고정한다 (`UR-260909-6`).
 *
 * <p>이 기능은 규칙 하나를 어기면 <b>사람이 눈치채지 못하는 방식으로</b> 망가진다.
 * <ul>
 *   <li>임시저장이 공유본을 건드리면 <b>SDE 가 보던 내용이 말없이 사라진다</b> — 이 기능이 생긴 이유가 그것이다</li>
 *   <li>초안이 SDE 응답에 실리면 <b>다 못 쓴 분석이 새어 나간다</b> — 화면에서 감춰도 개발자도구로 보인다</li>
 *   <li>공유하면서 초안을 안 비우면 화면 규칙에 걸려 <b>공유 직후 편집기로 튄다</b></li>
 *   <li>잠금 키({@code updated_at})의 정밀도가 응답과 DB 에서 다르면 <b>혼자 쓰는데도 계속 409</b> 가 난다</li>
 * </ul>
 * 셋 다 예외도 컴파일 에러도 없이 조용히 일어나므로, 경계를 여기서 못박는다.
 */
class RequestNoteDraftTest {

    private RequestNoteRepository noteRepo;
    private RequestNoteRevisionRepository revRepo;
    private NoteReadRepository readRepo;
    private ItsmRequestRepository reqRepo;
    private AppUserRepository userRepo;
    private DashboardService dashboard;
    private RequestNoteService svc;

    private static final String REQ = "CSD260909000001";
    private static final String CORP = "풀무원푸드앤컬처";
    private static final long SME_ID = 1, OTHER_SME_ID = 2, SDE_ID = 3;

    /** 이미 공유된 본문 — "SDE 가 지금 보고 있는 것". */
    private static final String PUBLISHED = "{\"ops\":[{\"insert\":\"1차 공유 내용\\n\"}]}";
    private static final String WORKING   = "{\"ops\":[{\"insert\":\"고치는 중\\n\"}]}";

    private static AppUser user(long id, String role, String corpNm) {
        AppUser u = new AppUser();
        u.setId(id); u.setRole(role); u.setCorpNm(corpNm);
        u.setLoginId("u" + id); u.setName("사용자" + id);
        return u;
    }

    private static UserSession session(long userId, String role) {
        UserSession s = new UserSession();
        s.setUserId(userId); s.setRole(role); s.setLoginId("u" + userId);
        return s;
    }

    /** 이미 한 번 공유된 노트. 대부분의 사고는 이 상태에서 일어난다. */
    private RequestNote publishedNote() {
        RequestNote n = new RequestNote();
        n.setId(10L); n.setReqNo(REQ);
        n.setBodyDelta(PUBLISHED); n.setBodyText("1차 공유 내용");
        n.setAuthorId(SME_ID);
        n.setPublishedAt(LocalDateTime.of(2026, 9, 9, 9, 0));
        n.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 9, 0));
        return n;
    }

    @BeforeEach
    void setUp() {
        noteRepo = mock(RequestNoteRepository.class);
        revRepo = mock(RequestNoteRevisionRepository.class);
        reqRepo = mock(ItsmRequestRepository.class);
        userRepo = mock(AppUserRepository.class);
        dashboard = mock(DashboardService.class);
        readRepo = mock(NoteReadRepository.class);
        lenient().when(readRepo.findByReqNoAndUserId(anyString(), any())).thenReturn(Optional.empty());
        lenient().when(readRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        svc = new RequestNoteService(noteRepo, revRepo, readRepo, reqRepo, userRepo, dashboard, new ObjectMapper());

        ItsmRequest r = new ItsmRequest();
        r.setReqNo(REQ); r.setReqCompNm(CORP);
        lenient().when(reqRepo.findByReqNo(REQ)).thenReturn(Optional.of(r));

        lenient().when(userRepo.findById(SME_ID)).thenReturn(Optional.of(user(SME_ID, "SME", CORP)));
        lenient().when(userRepo.findById(OTHER_SME_ID)).thenReturn(Optional.of(user(OTHER_SME_ID, "SME", CORP)));
        lenient().when(userRepo.findById(SDE_ID)).thenReturn(Optional.of(user(SDE_ID, "SDE", null)));

        lenient().when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.empty());
        lenient().when(noteRepo.save(any(RequestNote.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(revRepo.save(any(RequestNoteRevision.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(revRepo.countByReqNo(anyString())).thenReturn(0L);
        lenient().when(revRepo.findByReqNoOrderBySeqDesc(anyString())).thenReturn(List.of());
    }

    private NoteSaveRequest draft(String delta)  { return new NoteSaveRequest(delta, null, "DRAFT", null); }
    private NoteSaveRequest publish(String delta, String memo) {
        return new NoteSaveRequest(delta, null, "PUBLISH", memo);
    }

    @Test
    void 임시저장은_공유본을_건드리지_않는다() {
        // ⚠️ 이 기능이 존재하는 이유. 여기가 무너지면 SDE 가 보던 내용이 말없이 사라진다.
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(publishedNote()));

        svc.save(session(SME_ID, "SME"), REQ, draft(WORKING));

        ArgumentCaptor<RequestNote> saved = ArgumentCaptor.forClass(RequestNote.class);
        verify(noteRepo).save(saved.capture());
        assertThat(saved.getValue().getBodyDelta()).isEqualTo(PUBLISHED);      // 공유본 그대로
        assertThat(saved.getValue().getBodyText()).isEqualTo("1차 공유 내용");
        assertThat(saved.getValue().getDraftDelta()).contains("고치는 중");     // 초안에만 들어간다
        verify(revRepo, never()).save(any());                                  // 임시저장은 이력을 남기지 않는다
    }

    @Test
    void SDE_응답에는_초안이_아예_없다() {
        // 화면에서 감추는 게 아니라 **응답에서 뺀다** — 개발자도구로 보이면 숨긴 의미가 없다
        RequestNote n = publishedNote();
        n.setDraftDelta(WORKING);
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(n));

        var forSde = svc.get(session(SDE_ID, "SDE"), REQ);
        assertThat(forSde.editable()).isFalse();
        assertThat(forSde.draftDelta()).isNull();
        assertThat(forSde.state()).isEqualTo("READ");
        assertThat(forSde.bodyDelta()).isEqualTo(PUBLISHED);      // 읽는 사람은 공유본만 본다
    }

    @Test
    void 같은_법인_다른_SME_는_초안을_본다() {
        // 작성자에게만 보이게 하면 SME 둘이 서로 안 보이는 초안을 각자 쓰다 덮어쓴다
        RequestNote n = publishedNote();
        n.setDraftDelta(WORKING);
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(n));

        var forOther = svc.get(session(OTHER_SME_ID, "SME"), REQ);
        assertThat(forOther.editable()).isTrue();
        assertThat(forOther.draftDelta()).contains("고치는 중");
        assertThat(forOther.state()).isEqualTo("EDIT");
    }

    @Test
    void 공유하면_초안이_비고_이력이_한_행_쌓인다() {
        // ⚠️ 초안을 안 비우면 화면 규칙("초안 있으면 편집 상태")에 걸려 공유 직후 편집기로 튄다
        RequestNote n = publishedNote();
        n.setDraftDelta(WORKING);
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(n));
        when(revRepo.countByReqNo(REQ)).thenReturn(1L);

        svc.save(session(SME_ID, "SME"), REQ, publish(WORKING, "처리 방식을 B 로 변경"));

        ArgumentCaptor<RequestNote> saved = ArgumentCaptor.forClass(RequestNote.class);
        verify(noteRepo).save(saved.capture());
        assertThat(saved.getValue().getBodyDelta()).contains("고치는 중");
        assertThat(saved.getValue().getDraftDelta()).isNull();
        assertThat(saved.getValue().getDraftUpdatedAt()).isNull();
        assertThat(saved.getValue().getPublishedAt()).isNotNull();

        ArgumentCaptor<RequestNoteRevision> rev = ArgumentCaptor.forClass(RequestNoteRevision.class);
        verify(revRepo).save(rev.capture());
        assertThat(rev.getValue().getSeq()).isEqualTo(2);                       // 1차가 있으니 2차
        assertThat(rev.getValue().getPublishMemo()).isEqualTo("처리 방식을 B 로 변경");
        assertThat(rev.getValue().getBodyText()).isEqualTo("고치는 중");
    }

    @Test
    void 두번째_공유부터_변경_사유가_필수다() {
        // 혼란의 정체는 "이전 걸 못 본다" 가 아니라 "바뀐 줄 모른다" 였다
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(publishedNote()));
        when(revRepo.countByReqNo(REQ)).thenReturn(1L);

        assertThatThrownBy(() -> svc.save(session(SME_ID, "SME"), REQ, publish(WORKING, "   ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("한 줄 남겨");
        verify(revRepo, never()).save(any());
    }

    @Test
    void 첫_공유는_변경_사유를_받지_않는다() {
        // 바꾼 게 없는데 "무엇을 바꿨나" 를 물으면 아무 말이나 적게 된다
        svc.save(session(SME_ID, "SME"), REQ, publish(WORKING, null));

        ArgumentCaptor<RequestNoteRevision> rev = ArgumentCaptor.forClass(RequestNoteRevision.class);
        verify(revRepo).save(rev.capture());
        assertThat(rev.getValue().getSeq()).isEqualTo(1);
        assertThat(rev.getValue().getPublishMemo()).isNull();
    }

    @Test
    void 빈_내용은_공유할_수_없다() {
        // 배지도 안 뜨는 빈 이력이 쌓인다. 지우는 뜻이라면 초안 삭제가 맞는 문이다.
        assertThatThrownBy(() -> svc.save(session(SME_ID, "SME"), REQ, publish("{\"ops\":[]}", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("내용을 입력");
        verify(revRepo, never()).save(any());
    }

    @Test
    void 초안_삭제는_공유본을_남긴다() {
        // 편집 상태에서 빠져나오는 유일한 문. 지우는 것은 아직 아무도 못 본 작업본뿐이다.
        RequestNote n = publishedNote();
        n.setDraftDelta(WORKING);
        when(noteRepo.findByReqNo(REQ)).thenReturn(Optional.of(n));

        var after = svc.discardDraft(session(SME_ID, "SME"), REQ);

        assertThat(n.getDraftDelta()).isNull();
        assertThat(n.getBodyDelta()).isEqualTo(PUBLISHED);
        assertThat(after.state()).isEqualTo("READ");
        verify(revRepo, never()).save(any());
    }

    @Test
    void 이력도_본문과_같은_조회_범위_검사를_탄다() {
        // 이력만 따로 새면 노트를 숨긴 의미가 없다
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(dashboard).detail(any(), eq(REQ));

        assertThatThrownBy(() -> svc.revisions(session(SDE_ID, "SDE"), REQ))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> svc.revision(session(SDE_ID, "SDE"), REQ, 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 이력_목록은_최신에_현재_표시를_붙이고_미리보기를_평평하게_만든다() {
        when(revRepo.findByReqNoOrderBySeqDesc(REQ)).thenReturn(List.of(
                revision(2, "두\n번째  공유"), revision(1, "첫 번째")));

        var rows = svc.revisions(session(SDE_ID, "SDE"), REQ);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).seq()).isEqualTo(2);
        assertThat(rows.get(0).current()).isTrue();
        assertThat(rows.get(1).current()).isFalse();
        assertThat(rows.get(0).preview()).isEqualTo("두 번째 공유");   // 줄바꿈이 목록을 무너뜨리지 않게
    }

    @Test
    void 같은_화면에서_연속으로_두_번_저장해도_충돌이_아니다() {
        // 2026-09-10 회귀. 잠금 키가 `updated_at` 인데 컬럼이 DATETIME(소수점 자리수 0) 이라,
        // 응답은 **메모리 값**(소수점 있음)이고 다음 요청이 읽는 것은 **DB 값**(소수점 없음)이었다.
        // 화면은 응답의 값을 그대로 되돌려 보내므로, 저장에 성공한 그 화면에서의 두 번째 저장은
        // 언제나 409 였다 — "다른 사람이 먼저 저장했습니다" 라는 거짓 안내와 함께.
        storeLikeDatetime0Column(publishedNote());
        when(revRepo.countByReqNo(REQ)).thenReturn(1L);
        UserSession sme = session(SME_ID, "SME");

        var afterDraft = svc.save(sme, REQ, new NoteSaveRequest(WORKING, currentRow.getUpdatedAt(), "DRAFT", null));

        // 응답의 잠금 키가 DB 에 담기지 못하는 정밀도면 그 뒤 무엇을 해도 맞출 수 없다
        assertThat(afterDraft.updatedAt().getNano()).isZero();

        // 화면이 하는 그대로 — 방금 받은 값을 다음 저장의 잠금 키로 되돌려 보낸다
        svc.save(sme, REQ, new NoteSaveRequest(WORKING, afterDraft.updatedAt(), "PUBLISH", "표 한 줄 추가"));

        verify(revRepo).save(any(RequestNoteRevision.class));      // 공유가 실제로 됐다
    }

    @Test
    void 정말_다른_사람이_먼저_저장했으면_거부한다() {
        // 위 테스트가 잠금을 꺼 버리는 방식으로 통과하지 않게 반대편도 못박는다
        storeLikeDatetime0Column(publishedNote());

        LocalDateTime 내가_열었을_때 = currentRow.getUpdatedAt();
        svc.save(session(OTHER_SME_ID, "SME"), REQ, draft(WORKING));    // 그 사이 다른 SME 가 저장

        assertThatThrownBy(() -> svc.save(session(SME_ID, "SME"), REQ,
                new NoteSaveRequest(WORKING, 내가_열었을_때, "PUBLISH", "내 내용으로 덮어쓰기")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("다른 사람이 먼저 저장");
    }

    /** DB 에 있는 행. 아래 가짜 저장소가 <b>DATETIME(0) 정밀도로</b> 들고 있는 값이다. */
    private RequestNote currentRow;

    /**
     * 노트 저장소를 <b>실제 컬럼처럼</b> 행동하게 만든다 — 저장하는 순간 소수점이 사라지고,
     * 다음 조회는 그 잘린 값을 돌려준다. 응답은 넘겨받은 <b>메모리 객체</b>로 조립된다(운영과 같다).
     *
     * <p>⚠️ 저장 인자를 그대로 되돌려 주는 흔한 목으로는 이 고장이 <b>절대 재현되지 않는다</b> —
     * 응답과 DB 가 같은 객체가 되어 정밀도 차이가 사라지기 때문이다.
     */
    private void storeLikeDatetime0Column(RequestNote initial) {
        currentRow = initial;
        when(noteRepo.findByReqNo(REQ)).thenAnswer(i -> Optional.ofNullable(currentRow));
        when(noteRepo.save(any(RequestNote.class))).thenAnswer(i -> {
            RequestNote inMemory = i.getArgument(0);
            currentRow = asStoredRow(inMemory);
            return inMemory;
        });
    }

    /** 컬럼에 담긴 뒤의 값 — 시각은 초까지만 남는다. */
    private static RequestNote asStoredRow(RequestNote n) {
        RequestNote row = new RequestNote();
        row.setId(n.getId() == null ? 10L : n.getId());
        row.setReqNo(n.getReqNo());
        row.setBodyDelta(n.getBodyDelta());
        row.setBodyText(n.getBodyText());
        row.setDraftDelta(n.getDraftDelta());
        row.setAuthorId(n.getAuthorId());
        row.setDraftUpdatedAt(toSeconds(n.getDraftUpdatedAt()));
        row.setPublishedAt(toSeconds(n.getPublishedAt()));
        row.setUpdatedAt(toSeconds(n.getUpdatedAt()));
        return row;
    }

    private static LocalDateTime toSeconds(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.SECONDS);
    }

    private RequestNoteRevision revision(int seq, String text) {
        RequestNoteRevision v = new RequestNoteRevision();
        v.setReqNo(REQ); v.setSeq(seq); v.setBodyText(text);
        v.setAuthorId(SME_ID); v.setPublishedAt(LocalDateTime.of(2026, 9, 9, 9, seq));
        return v;
    }
}
