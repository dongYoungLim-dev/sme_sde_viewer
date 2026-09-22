package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.RequestDtos.*;

import com.pulmuone.sdeboard.domain.AppTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestNote;
import com.pulmuone.sdeboard.domain.NoteRead;
import com.pulmuone.sdeboard.domain.RequestNoteRevision;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestNoteRepository;
import com.pulmuone.sdeboard.repo.NoteReadRepository;
import com.pulmuone.sdeboard.repo.RequestNoteRevisionRepository;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 요청 분석 노트 — SME 가 쓰고, 조회 범위 안의 모두가 읽는다.
 *
 * <p><b>쓰기 권한은 그 요청 법인의 SME 뿐이다.</b> 리더·SDE 는 읽기만 한다 —
 * "SME 가 먼저 분석해 전달한다" 가 이 기능의 목적이고, 아무나 고칠 수 있으면 누가 쓴 문서인지 흐려진다.
 *
 * <p><b>저장은 임시저장(DRAFT)과 공유(PUBLISH) 둘이다</b>(2026-09-09 `UR-260909-6`).
 * 본문을 <b>두 벌</b>로 둔 것이 핵심이다 — 한 행에 하나뿐이면 공유한 노트를 고치다 임시저장하는 순간
 * <b>SDE 가 보던 내용이 사라진다.</b> 읽는 쪽은 언제나 {@code bodyDelta}(공유본)만 본다.
 * 공유할 때마다 {@link RequestNoteRevision} 이 한 행 쌓인다 — 덮어써서 사라지지 않게.
 *
 * <p><b>저장 형식은 HTML 이 아니라 Quill Delta(JSON) 다.</b> 마크업을 저장하면 그리는 순간
 * 주입 경로가 생기고 서버 HTML 새니타이저가 필요해진다. 여기서는 들어온 ops 를 <b>다시 조립</b>하므로
 * (모르는 속성은 버린다) 저장되는 것은 우리가 아는 구조뿐이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RequestNoteService {

    private final RequestNoteRepository noteRepo;
    private final RequestNoteRevisionRepository revRepo;
    private final NoteReadRepository readRepo;
    private final ItsmRequestRepository reqRepo;
    private final AppUserRepository userRepo;
    private final DashboardService dashboard;
    private final ObjectMapper mapper;

    /** 저장 상한. 요구사항 정의서 한 장이 이걸 넘길 이유가 없고, 상한이 없으면 DB 가 사고를 대신 맞는다. */
    static final int MAX_DELTA_BYTES = 512 * 1024;

    /** 변경 사유 한 줄의 상한. 컬럼이 300 이라 여기서 먼저 막지 않으면 DB 가 잘라 버린다. */
    static final int MAX_MEMO_CHARS = 300;

    /**
     * 남겨도 되는 서식. **모르는 속성은 버린다**(allowlist).
     * 이미지·비디오는 1단계에서 뺐다 — 붙이는 순간 파일 저장·용량·백업이 따라온다.
     */
    private static final Set<String> ALLOWED_ATTRS = Set.of(
            "bold", "italic", "underline", "strike", "code",
            "header", "list", "indent", "blockquote", "code-block", "align", "script", "link");

    /** 링크에 허용하는 스킴. `javascript:` 류를 막는 유일한 지점이라 여기서 확실히 건다. */
    private static final List<String> LINK_SCHEMES = List.of("http://", "https://", "mailto:");

    /**
     * 노트 한 건. <b>여는 순간이 곧 '읽음' 이다</b> — 목록의 `노트 갱신` 배지가 여기서 꺼진다.
     * ⚠️ 그래서 이 메서드는 읽기 전용이 아니다.
     */
    @Transactional
    public NoteResponse get(UserSession session, String reqNo) {
        ItsmRequest r = requireInScope(session, reqNo);
        RequestNote n = noteRepo.findByReqNo(reqNo).orElse(null);
        // 내용이 있는 노트만 읽음으로 남긴다 — 빈 노트를 스친 기록은 아무것도 알려주지 않는다
        if (n != null && n.getBodyText() != null && !n.getBodyText().isBlank())
            markRead(reqNo, session.getUserId());
        return toDto(r, n, canEdit(session, r));
    }

    /**
     * 읽음 시각 갱신 — 요청·사용자당 한 행(UPSERT).
     * 공유한 본인에게도 찍는다: 자기가 방금 쓴 글에 `갱신` 배지가 뜨면 배지를 안 믿게 된다.
     */
    private void markRead(String reqNo, Long userId) {
        if (userId == null) return;
        NoteRead nr = readRepo.findByReqNoAndUserId(reqNo, userId).orElseGet(() -> {
            NoteRead fresh = new NoteRead();
            fresh.setReqNo(reqNo);
            fresh.setUserId(userId);
            return fresh;
        });
        nr.setReadAt(AppTime.now());
        readRepo.save(nr);
    }

    /**
     * 공유 이력 목록 — 팝업 왼쪽. <b>본문(Delta)은 담지 않는다</b>(목록에 못 쓰고, 무겁다).
     * ⚠️ 본문과 <b>같은 조회 범위 검사</b>를 탄다 — 이력만 따로 새면 노트를 숨긴 의미가 없다.
     */
    public List<NoteRevisionView> revisions(UserSession session, String reqNo) {
        requireInScope(session, reqNo);
        List<RequestNoteRevision> all = revRepo.findByReqNoOrderBySeqDesc(reqNo);
        int latest = all.isEmpty() ? 0 : all.get(0).getSeq();
        return all.stream()
                .map(v -> new NoteRevisionView(v.getSeq(), v.getPublishedAt(), authorName(v.getAuthorId()),
                        v.getPublishMemo(), preview(v.getBodyText()), v.getSeq() == latest))
                .toList();
    }

    /** 공유 이력 한 건 — 팝업 오른쪽(읽기 전용). */
    public NoteRevisionDetailView revision(UserSession session, String reqNo, int seq) {
        requireInScope(session, reqNo);
        RequestNoteRevision v = revRepo.findByReqNoAndSeq(reqNo, seq)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 공유 이력입니다."));
        int latest = revRepo.findByReqNoOrderBySeqDesc(reqNo).stream()
                .findFirst().map(RequestNoteRevision::getSeq).orElse(0);
        return new NoteRevisionDetailView(v.getSeq(), v.getPublishedAt(), authorName(v.getAuthorId()),
                v.getPublishMemo(), v.getBodyDelta(), v.getSeq() == latest);
    }

    /** 이력 목록의 미리보기. 줄바꿈을 지우지 않으면 목록 한 줄이 무너진다. */
    private String preview(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    private String authorName(Long userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(AppUser::getName).orElse("(탈퇴)");
    }

    /**
     * 노트 저장 — <b>임시저장(DRAFT)</b> 또는 <b>공유(PUBLISH)</b>.
     *
     * <p>임시저장은 {@code draft_*} 만 건드린다. 그래서 <b>SDE 가 보고 있는 공유본은 그대로 남는다</b> —
     * 이 기능이 존재하는 이유가 그것이다.
     *
     * <p>공유는 <b>한 트랜잭션에서 셋을 함께</b> 한다: 공유본 갱신 · 초안 비우기 · 이력 한 행 추가.
     * ⚠️ 초안을 안 비우면 화면 규칙("초안이 있으면 편집 상태")에 걸려 <b>공유 직후 다시 편집기로 튄다.</b>
     */
    @Transactional
    public NoteResponse save(UserSession session, String reqNo, NoteSaveRequest req) {
        ItsmRequest r = requireInScope(session, reqNo);
        if (!canEdit(session, r))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "분석 노트는 해당 법인의 SME 만 작성할 수 있습니다.");

        boolean publish = !"DRAFT".equalsIgnoreCase(req.mode());     // 기본은 공유(옛 클라이언트 호환)
        String delta = normalizeDelta(req.bodyDelta());
        String text = plainText(delta);

        RequestNote n = noteRepo.findByReqNo(reqNo).orElseGet(() -> {
            RequestNote fresh = new RequestNote();
            fresh.setReqNo(reqNo);
            return fresh;
        });
        // 낙관적 잠금 — 내가 열어 둔 사이에 다른 SME 가 저장했으면 덮어쓰지 않는다.
        // ⚠️ 초안 저장도 이 키를 올린다. 키를 둘로 나누면 설명 못 할 상태가 생긴다.
        if (n.getId() != null && req.expectedUpdatedAt() != null
                && !req.expectedUpdatedAt().equals(n.getUpdatedAt()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "다른 사람이 먼저 저장했습니다. 새로고침해 최신 내용을 확인한 뒤 다시 작성하세요.");

        LocalDateTime now = AppTime.now();
        n.setAuthorId(session.getUserId());
        n.setUpdatedAt(now);

        if (!publish) {                                  // ── 임시저장: 공유본은 손대지 않는다
            n.setDraftDelta(delta);
            n.setDraftUpdatedAt(now);
            noteRepo.save(n);
            log.info("분석 노트 임시저장 reqNo={} by={} chars={}", reqNo, session.getLoginId(), text.length());
            return toDto(r, n, true);
        }

        // ── 공유
        // 빈 내용을 공유하면 배지도 안 뜨는 빈 이력이 쌓인다. 지우는 뜻이면 초안 삭제가 맞는 문이다.
        if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용을 입력한 뒤 공유하세요.");

        int seq = (int) revRepo.countByReqNo(reqNo) + 1;
        // 2차 공유부터 변경 사유 필수 (사용자 결정 2026-09-09) — 이력 목록이 이 값으로 산다.
        // 첫 공유는 바꾼 게 없으므로 받지 않는다.
        String memo = trimMemo(req.publishMemo());
        if (seq > 1 && memo == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "무엇을 바꿨는지 한 줄 남겨 주세요 — 읽는 사람이 바뀐 줄 모르면 이전 내용으로 작업하게 됩니다.");
        if (seq == 1) memo = null;

        n.setBodyDelta(delta);
        n.setBodyText(text);
        n.setPublishedAt(now);
        n.setDraftDelta(null);                           // ⚠️ 비우지 않으면 공유 직후 편집기로 튄다
        n.setDraftUpdatedAt(null);
        noteRepo.save(n);

        RequestNoteRevision rev = new RequestNoteRevision();
        rev.setReqNo(reqNo);
        rev.setSeq(seq);
        rev.setBodyDelta(delta);
        rev.setBodyText(text);
        rev.setPublishMemo(memo);
        rev.setAuthorId(session.getUserId());
        rev.setPublishedAt(now);
        revRepo.save(rev);

        markRead(reqNo, session.getUserId());     // 내가 방금 쓴 글에 `갱신` 이 뜨면 배지를 안 믿게 된다

        log.info("분석 노트 공유 reqNo={} seq={} by={} chars={} memo={}",
                reqNo, seq, session.getLoginId(), text.length(), memo);
        return toDto(r, n, true);
    }

    /**
     * 초안 폐기 — 편집 상태에서 빠져나오는 <b>유일한 문</b>이다.
     *
     * <p>없으면 실수로 임시저장한 노트가 <b>영원히 편집 상태</b>로 남는다(초안이 있으면 편집 유지가 규칙이라).
     * ⚠️ <b>공유본은 건드리지 않는다</b> — 지우는 것은 아직 아무도 못 본 작업본뿐이다.
     */
    @Transactional
    public NoteResponse discardDraft(UserSession session, String reqNo) {
        ItsmRequest r = requireInScope(session, reqNo);
        if (!canEdit(session, r))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "분석 노트는 해당 법인의 SME 만 작성할 수 있습니다.");

        RequestNote n = noteRepo.findByReqNo(reqNo).orElse(null);
        if (n == null || n.getDraftDelta() == null) return toDto(r, n, true);   // 버릴 게 없으면 그대로

        n.setDraftDelta(null);
        n.setDraftUpdatedAt(null);
        n.setUpdatedAt(AppTime.now());
        noteRepo.save(n);
        log.info("분석 노트 초안 삭제 reqNo={} by={}", reqNo, session.getLoginId());
        return toDto(r, n, true);
    }

    private String trimMemo(String memo) {
        if (memo == null) return null;
        String t = memo.strip();
        if (t.isEmpty()) return null;
        return t.length() <= MAX_MEMO_CHARS ? t : t.substring(0, MAX_MEMO_CHARS);
    }

    // ── 권한 · 범위

    /** 조회 범위 밖이면 존재 자체를 알리지 않는다 — 상세 조회와 같은 규칙을 그대로 탄다. */
    private ItsmRequest requireInScope(UserSession session, String reqNo) {
        dashboard.detail(session, reqNo);          // 범위 밖이면 여기서 404
        return reqRepo.findByReqNo(reqNo).orElseThrow();
    }

    /** 쓰기 = **그 요청 법인의 SME**. 법인 비교는 조회 범위와 같은 규칙(공백 무시)을 쓴다. */
    private boolean canEdit(UserSession session, ItsmRequest r) {
        AppUser me = userRepo.findById(session.getUserId()).orElse(null);
        if (me == null || !"SME".equals(me.getRole()) || me.getCorpNm() == null) return false;
        return DashboardService.normCorp(me.getCorpNm()).equals(DashboardService.normCorp(r.getReqCompNm()));
    }

    // ── Delta 정규화 (여기가 보안 경계다)

    /**
     * 들어온 Delta 를 **다시 조립한다.** 통과시키는 게 아니라 우리가 아는 것만 새로 쓴다 —
     * 모르는 속성, 문자열이 아닌 insert(이미지·비디오 임베드), 허용되지 않은 링크 스킴은 전부 사라진다.
     */
    String normalizeDelta(String raw) {
        if (raw == null || raw.isBlank()) return emptyDelta();
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DELTA_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "노트가 너무 큽니다 (최대 " + (MAX_DELTA_BYTES / 1024) + "KB).");
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "노트 형식이 올바르지 않습니다.");
        }
        JsonNode ops = root.path("ops");
        if (!ops.isArray()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "노트 형식이 올바르지 않습니다.");

        ArrayNode out = mapper.createArrayNode();
        for (JsonNode op : ops) {
            JsonNode insert = op.path("insert");
            if (!insert.isTextual()) continue;                 // 임베드(이미지 등)는 1단계에서 제외
            ObjectNode clean = mapper.createObjectNode();
            clean.put("insert", insert.asText());
            ObjectNode attrs = cleanAttributes(op.path("attributes"));
            if (!attrs.isEmpty()) clean.set("attributes", attrs);
            out.add(clean);
        }
        ObjectNode doc = mapper.createObjectNode();
        doc.set("ops", out);
        return doc.toString();
    }

    private ObjectNode cleanAttributes(JsonNode attrs) {
        ObjectNode out = mapper.createObjectNode();
        if (!attrs.isObject()) return out;
        attrs.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (!ALLOWED_ATTRS.contains(k)) return;            // 모르는 서식은 버린다
            JsonNode v = e.getValue();
            if (v.isNull()) return;
            if ("link".equals(k)) {
                String url = v.asText("").trim();
                String lower = url.toLowerCase(Locale.ROOT);
                if (LINK_SCHEMES.stream().noneMatch(lower::startsWith)) return;   // javascript: 등 차단
                out.put("link", url);
                return;
            }
            if (v.isTextual()) out.put(k, v.asText());
            else if (v.isBoolean()) out.put(k, v.asBoolean());
            else if (v.isNumber()) out.put(k, v.asInt());
        });
        return out;
    }

    /** 평문 파생 — 검색·미리보기·내보내기용. 서식은 버리고 글자만 남긴다. */
    String plainText(String delta) {
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode op : mapper.readTree(delta).path("ops"))
                if (op.path("insert").isTextual()) sb.append(op.path("insert").asText());
            return sb.toString().strip();
        } catch (Exception e) {
            return "";
        }
    }

    private String emptyDelta() {
        return "{\"ops\":[]}";
    }

    /**
     * 응답 조립.
     *
     * <p>⚠️ <b>{@code draftDelta} 는 편집 권한이 있을 때만 채운다.</b> 화면에서 감추는 게 아니라
     * 응답에서 뺀다 — SDE 가 개발자도구로 볼 수 있으면 초안을 숨긴 의미가 없다.
     * <p>{@code state} 는 화면이 그대로 쓰는 값이다: {@code EMPTY} · {@code READ} · {@code EDIT}.
     */
    private NoteResponse toDto(ItsmRequest r, RequestNote n, boolean editable) {
        if (n == null)
            return new NoteResponse(r.getReqNo(), emptyDelta(), "", null, null, null, editable, false,
                    null, null, null, "EMPTY", 0);

        AppUser author = n.getAuthorId() == null ? null : userRepo.findById(n.getAuthorId()).orElse(null);
        boolean hasContent = n.getBodyText() != null && !n.getBodyText().isBlank();
        boolean hasDraft = editable && n.getDraftDelta() != null;
        String state = hasDraft ? "EDIT" : (hasContent ? "READ" : "EMPTY");

        return new NoteResponse(r.getReqNo(), n.getBodyDelta(), n.getBodyText(),
                n.getAuthorId(), author == null ? null : author.getName(), n.getUpdatedAt(),
                editable, hasContent,
                hasDraft ? n.getDraftDelta() : null,
                hasDraft ? n.getDraftUpdatedAt() : null,
                n.getPublishedAt(), state, (int) revRepo.countByReqNo(r.getReqNo()));
    }
}
