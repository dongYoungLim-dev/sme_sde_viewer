package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppTime;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestFile;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.RequestFileRepository;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.web.dto.FileDtos.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SME 첨부파일 (`UR-260922-1`) — **이 보드가 원본을 갖는 첫 바이너리**다.
 *
 * <p>사용자 결정: 업로드는 <b>SME 만</b> · 엑셀/워드/PPT/이미지 · <b>건당 10MB · 요청당 5개</b> · 삭제는 <b>물리 삭제</b>.
 * 조회·다운로드는 그 요청을 볼 수 있는 사람 전원(SDE·리더 포함)이다.
 *
 * <p><b>파일을 신뢰하지 않는다</b> — 사용자가 올린 이름은 화면 표시용일 뿐 경로에 절대 쓰지 않는다(저장은 난수 이름).
 * 확장자 화이트리스트에 더해 <b>파일 첫 바이트(매직 넘버)</b>가 확장자와 맞는지 본다. 확장자만 바꾼 실행 파일을
 * 막는 최소한의 장치다. 내려받을 땐 항상 `attachment` 로 내려 브라우저가 열어 실행하지 못하게 한다.
 */
@Service
@Slf4j
public class RequestFileService {

    public static final int MAX_FILES = 5;
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 확장자 → 서버가 정한 Content-Type. 클라이언트가 보낸 값은 쓰지 않는다. */
    static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        TYPES.put("xls", "application/vnd.ms-excel");
        TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        TYPES.put("doc", "application/msword");
        TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        TYPES.put("ppt", "application/vnd.ms-powerpoint");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
    }

    private static final Set<String> ZIP_TYPES = Set.of("xlsx", "docx", "pptx");
    private static final Set<String> OLE_TYPES = Set.of("xls", "doc", "ppt");

    private final RequestFileRepository fileRepo;
    private final AppUserRepository userRepo;
    private final DashboardService dashboard;
    private final Path root;

    public RequestFileService(RequestFileRepository fileRepo, AppUserRepository userRepo, DashboardService dashboard,
                              @Value("${app.upload-dir:/data/uploads}") String uploadDir) {
        this.fileRepo = fileRepo;
        this.userRepo = userRepo;
        this.dashboard = dashboard;
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public record Download(String fileName, String contentType, byte[] data) {}

    // ── 조회 ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FilesResponse list(UserSession s, String reqNo) {
        ItsmRequest r = dashboard.requireInScopeRequest(s, reqNo);
        List<RequestFile> rows = fileRepo.findByReqNoOrderByIdAsc(reqNo);
        Map<Long, String> names = userRepo.findAllById(rows.stream().map(RequestFile::getUploadedBy).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, u -> u.getName() == null ? u.getLoginId() : u.getName(), (a, b) -> a));
        boolean sme = "SME".equals(s.getRole());
        List<FileView> files = rows.stream().map(f -> new FileView(f.getId(), f.getReqNo(), f.getFileName(), f.getFileSize(),
                f.getContentType(), f.getUploadedBy(), names.get(f.getUploadedBy()), f.getUploadedAt(), sme)).toList();
        String blocked = uploadBlockedReason(s, r, rows.size());
        return new FilesResponse(files, blocked == null, blocked, MAX_FILES, MAX_BYTES, List.copyOf(TYPES.keySet()));
    }

    private String uploadBlockedReason(UserSession s, ItsmRequest r, int count) {
        if (!"SME".equals(s.getRole())) return "첨부파일은 SME 만 올릴 수 있습니다.";
        if (BoardStatus.DONE.equals(r.getWorkStatus())) return "작업완료된 요청에는 파일을 올릴 수 없습니다.";
        if (count >= MAX_FILES) return "첨부는 요청당 최대 " + MAX_FILES + "개까지입니다. 하나를 삭제한 뒤 올리세요.";
        return null;
    }

    // ── 업로드 ───────────────────────────────────────────────────────────

    @Transactional
    public FileView upload(UserSession s, String reqNo, MultipartFile file) {
        ItsmRequest r = dashboard.requireInScopeRequest(s, reqNo);
        String blocked = uploadBlockedReason(s, r, (int) fileRepo.countByReqNo(reqNo));
        if (blocked != null) throw new ResponseStatusException("SME".equals(s.getRole()) ? HttpStatus.CONFLICT : HttpStatus.FORBIDDEN, blocked);

        if (file == null || file.isEmpty()) throw bad("빈 파일은 올릴 수 없습니다.");
        if (file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다. 건당 10MB 이하만 올릴 수 있습니다.");

        String display = displayName(file.getOriginalFilename());
        String ext = extensionOf(display);
        if (!TYPES.containsKey(ext))
            throw bad("올릴 수 없는 형식입니다. 허용: 엑셀·워드·PPT·이미지(" + String.join(", ", TYPES.keySet()) + ")");

        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 읽지 못했습니다.");
        }
        if (!matchesMagic(ext, head)) throw bad("파일 내용이 확장자(." + ext + ")와 맞지 않습니다.");

        String stored = UUID.randomUUID() + "." + ext;
        Path target = root.resolve(stored).normalize();
        if (!target.startsWith(root)) throw bad("잘못된 파일 이름입니다.");   // 방어선 — 난수 이름이라 실제로는 못 온다
        try {
            Files.createDirectories(root);
            file.transferTo(target);
        } catch (IOException e) {
            log.warn("첨부 저장 실패 reqNo={} : {}", reqNo, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 저장하지 못했습니다.");
        }

        RequestFile f = new RequestFile();
        f.setReqNo(reqNo);
        f.setFileName(display);
        f.setStoredName(stored);
        f.setContentType(TYPES.get(ext));
        f.setFileSize(file.getSize());
        f.setUploadedBy(s.getUserId());
        f.setUploadedAt(AppTime.now());
        try {
            fileRepo.save(f);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignore) { /* 고아 파일 — 다음 정리에서 */ }
            throw e;
        }
        return new FileView(f.getId(), reqNo, f.getFileName(), f.getFileSize(), f.getContentType(),
                f.getUploadedBy(), s.getName(), f.getUploadedAt(), true);
    }

    // ── 다운로드 ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Download download(UserSession s, Long id) {
        RequestFile f = requireFile(id);
        dashboard.requireInScopeRequest(s, f.getReqNo());       // 범위 밖이면 404 — 파일 존재 여부도 새지 않는다
        Path p = pathOf(f);
        try {
            return new Download(f.getFileName(), f.getContentType(), Files.readAllBytes(p));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다. (저장소에서 사라졌습니다)");
        }
    }

    // ── 삭제 (물리) ──────────────────────────────────────────────────────

    /** SME 만, 그 요청을 볼 수 있어야 한다. 파일과 행을 **함께 지운다**(복구 수단이 없다 — 사용자 결정). */
    @Transactional
    public void delete(UserSession s, Long id) {
        if (!"SME".equals(s.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "첨부파일은 SME 만 삭제할 수 있습니다.");
        RequestFile f = requireFile(id);
        dashboard.requireInScopeRequest(s, f.getReqNo());
        try {
            Files.deleteIfExists(pathOf(f));
        } catch (IOException e) {
            log.warn("첨부 파일 삭제 실패 id={} : {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일을 삭제하지 못했습니다.");
        }
        fileRepo.delete(f);
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private RequestFile requireFile(Long id) {
        return fileRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다."));
    }

    private Path pathOf(RequestFile f) {
        Path p = root.resolve(f.getStoredName()).normalize();
        if (!p.startsWith(root)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다.");
        return p;
    }

    /** 화면·다운로드에 쓸 이름 — 경로 구분자와 제어 문자를 걷어낸다. 저장 경로에는 쓰지 않는다. */
    static String displayName(String original) {
        String n = original == null ? "" : original;
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cntrl}]", "").trim();
        if (n.length() > 200) {
            String ext = extensionOf(n);
            n = n.substring(0, 200 - (ext.isEmpty() ? 0 : ext.length() + 1)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n.isEmpty() ? "file" : n;
    }

    static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** 확장자가 주장하는 형식과 파일 첫 바이트가 맞는가. */
    static boolean matchesMagic(String ext, byte[] h) {
        if (h == null) return false;
        if (ZIP_TYPES.contains(ext)) return h.length >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        if (OLE_TYPES.contains(ext)) return h.length >= 8 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11
                && (h[3] & 0xFF) == 0xE0 && (h[4] & 0xFF) == 0xA1 && (h[5] & 0xFF) == 0xB1 && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0xE1;
        return switch (ext) {
            case "png" -> h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "jpg", "jpeg" -> h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "gif" -> h.length >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
            default -> false;
        };
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
