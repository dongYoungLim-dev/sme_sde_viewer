package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestFile;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.RequestFileRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.FileDtos.FileView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SME 첨부파일(`UR-260922-1`) — **SME 만 · 허용 형식 · 10MB · 5개 · 물리 삭제**, 그리고 파일을 믿지 않는 것.
 */
class RequestFileServiceTest {

    private static final String REQ = "CSD1";
    private static final byte[] PK = {'P', 'K', 3, 4, 0, 0, 0, 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    @TempDir Path dir;
    private RequestFileRepository fileRepo;
    private DashboardService dashboard;
    private RequestFileService svc;
    private ItsmRequest req;
    private final List<RequestFile> stored = new ArrayList<>();

    private static UserSession session(long id, String role) {
        UserSession s = new UserSession();
        s.setUserId(id); s.setRole(role); s.setName("사용자" + id);
        return s;
    }

    @BeforeEach
    void setUp() {
        fileRepo = mock(RequestFileRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        dashboard = mock(DashboardService.class);
        svc = new RequestFileService(fileRepo, userRepo, dashboard, dir.toString());

        req = new ItsmRequest();
        req.setReqNo(REQ);
        req.setWorkStatus(BoardStatus.WAITING);
        when(dashboard.requireInScopeRequest(any(), eq(REQ))).thenReturn(req);
        when(fileRepo.save(any(RequestFile.class))).thenAnswer(inv -> {
            RequestFile f = inv.getArgument(0);
            f.setId((long) (stored.size() + 1)); stored.add(f); return f;
        });
        when(fileRepo.countByReqNo(REQ)).thenAnswer(inv -> (long) stored.size());
        when(fileRepo.findById(any())).thenAnswer(inv -> stored.stream().filter(f -> f.getId().equals(inv.getArgument(0))).findFirst());
        when(fileRepo.findByReqNoOrderByIdAsc(REQ)).thenAnswer(inv -> List.copyOf(stored));
        when(userRepo.findAllById(any())).thenReturn(List.of());
    }

    private static MockMultipartFile file(String name, byte[] head) {
        byte[] body = new byte[64];
        System.arraycopy(head, 0, body, 0, head.length);
        return new MockMultipartFile("file", name, "application/octet-stream", body);
    }

    private static void assertStatus(Throwable t, HttpStatus s) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(s));
    }

    @Test
    void SME_는_허용된_형식을_올릴_수_있고_실제_파일이_저장된다() throws Exception {
        FileView v = svc.upload(session(1, "SME"), REQ, file("요구사항.xlsx", PK));

        assertThat(v.fileName()).isEqualTo("요구사항.xlsx");
        RequestFile f = stored.get(0);
        assertThat(f.getStoredName()).endsWith(".xlsx").doesNotContain("요구사항");   // 저장 이름은 난수 — 원래 이름을 경로에 안 쓴다
        assertThat(Files.exists(dir.resolve(f.getStoredName()))).isTrue();
        assertThat(f.getContentType()).contains("spreadsheetml");                       // 서버가 정한 타입 — 클라이언트 값이 아니다
    }

    @Test
    void SME_가_아니면_올릴_수_없다() {
        for (String role : new String[]{"SDE", "SDE_LEADER"})
            assertThatThrownBy(() -> svc.upload(session(2, role), REQ, file("a.xlsx", PK))).satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThat(stored).isEmpty();
    }

    @Test
    void 허용되지_않은_확장자는_거절한다() {
        for (String name : new String[]{"run.exe", "a.sh", "a.html", "a.pdf", "a.svg", "noext"})
            assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, file(name, PK))).satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(stored).isEmpty();
    }

    @Test
    void 확장자만_바꾼_파일은_내용으로_걸러낸다() {
        // 실행 파일에 .png 만 붙인 경우
        assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, file("a.png", new byte[]{'M', 'Z', 0, 0, 0, 0, 0, 0})))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        // PNG 인데 xlsx 라고 주장
        assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, file("a.xlsx", PNG)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(svc.upload(session(1, "SME"), REQ, file("ok.png", PNG)).fileName()).isEqualTo("ok.png");
    }

    @Test
    void 십_메가를_넘으면_거절한다() {
        MockMultipartFile big = new MockMultipartFile("file", "big.xlsx", "x", new byte[(int) RequestFileService.MAX_BYTES + 1]);
        assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, big)).satisfies(t -> assertStatus(t, HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void 요청당_다섯_개까지만_올릴_수_있다() {
        for (int i = 0; i < RequestFileService.MAX_FILES; i++) svc.upload(session(1, "SME"), REQ, file("f" + i + ".xlsx", PK));
        assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, file("six.xlsx", PK))).satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(stored).hasSize(5);
    }

    @Test
    void 작업완료된_요청에는_올릴_수_없다() {
        req.setWorkStatus(BoardStatus.DONE);
        assertThatThrownBy(() -> svc.upload(session(1, "SME"), REQ, file("a.xlsx", PK))).satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void 경로_조작_이름은_표시_이름으로만_정리되고_저장_경로에_쓰이지_않는다() throws Exception {
        assertThat(RequestFileService.displayName("../../etc/passwd.xlsx")).isEqualTo("passwd.xlsx");
        assertThat(RequestFileService.displayName("C:\\Users\\a\\b.docx")).isEqualTo("b.docx");
        assertThat(RequestFileService.displayName("a\u0000b.png")).isEqualTo("ab.png");
        svc.upload(session(1, "SME"), REQ, file("../../evil.xlsx", PK));
        try (var s = Files.list(dir)) { assertThat(s.toList()).hasSize(1); }          // 업로드 폴더 밖으로 못 나간다
    }

    @Test
    void 삭제는_물리_삭제이고_SME_만_할_수_있다() throws Exception {
        FileView v = svc.upload(session(1, "SME"), REQ, file("a.xlsx", PK));
        Path onDisk = dir.resolve(stored.get(0).getStoredName());
        assertThat(Files.exists(onDisk)).isTrue();

        assertThatThrownBy(() -> svc.delete(session(2, "SDE"), v.id())).satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThat(Files.exists(onDisk)).isTrue();

        svc.delete(session(3, "SME"), v.id());           // 올린 사람이 아니어도 같은 법인 SME 면 지울 수 있다
        assertThat(Files.exists(onDisk)).isFalse();
        verify(fileRepo).delete(any(RequestFile.class));
    }

    @Test
    void 다운로드는_저장된_내용을_그대로_돌려준다() throws Exception {
        FileView v = svc.upload(session(1, "SME"), REQ, file("a.xlsx", PK));
        RequestFileService.Download d = svc.download(session(2, "SDE"), v.id());     // 조회는 범위 안이면 누구나
        assertThat(d.fileName()).isEqualTo("a.xlsx");
        assertThat(d.data()).hasSize(64);
    }

    @Test
    void 파일이_저장소에서_사라졌으면_404() throws Exception {
        FileView v = svc.upload(session(1, "SME"), REQ, file("a.xlsx", PK));
        Files.delete(dir.resolve(stored.get(0).getStoredName()));
        assertThatThrownBy(() -> svc.download(session(1, "SME"), v.id())).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void 목록은_SME_에게만_올리기_가능이라고_알려준다() {
        assertThat(svc.list(session(1, "SME"), REQ).canUpload()).isTrue();
        var forSde = svc.list(session(2, "SDE"), REQ);
        assertThat(forSde.canUpload()).isFalse();
        assertThat(forSde.uploadBlockedReason()).contains("SME");
        assertThat(forSde.maxFiles()).isEqualTo(5);
        assertThat(forSde.allowedExtensions()).contains("xlsx", "docx", "pptx", "png");
    }
}
