package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.web.dto.RequestDtos.*;
import com.pulmuone.sdeboard.web.dto.CommentDtos.*;
import com.pulmuone.sdeboard.web.dto.StatsDtos.*;

import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.service.DashboardService;
import com.pulmuone.sdeboard.service.ExportService;
import com.pulmuone.sdeboard.service.RequestCommentService;
import com.pulmuone.sdeboard.service.RequestNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 대시보드 조회 — 전부 로그인 세션(X-Session) 범위로 제한된다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;
    private final ExportService export;
    private final RequestNoteService notes;
    private final RequestCommentService comments;
    private final SessionSupport sessionSupport;

    @GetMapping("/requests")
    public List<RequestView> list(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                 @RequestParam(defaultValue = "ALL") String filter) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.list(s, filter);
    }

    /**
     * 작업 완료 목록 — 기간(완료일) 안의 완료 건 + 법인별·담당자별 집계.
     * ⚠️ 완료일은 ITSM 원본이 아니라 **우리 관측 시각**이다(응답의 `note` 를 화면에 그대로 띄운다).
     */
    @GetMapping("/requests/done")
    public DoneListResponse done(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.doneList(s, from, to);
    }

    /**
     * 요청목록 XLSX 내보내기 — **화면에서 보고 있는 그대로**(현재 상태 필터 + 검색어).
     * 상태 필터가 미할당이면 현업이 채워 돌려줄 '우선순위' 빈 열이 붙는다.
     * 범위 검사는 목록 조회와 같은 경로(dashboard.list)를 그대로 타므로 권한이 새지 않는다.
     */
    @GetMapping("/requests/export")
    public ResponseEntity<byte[]> exportXlsx(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                             @RequestParam(defaultValue = "ALL") String filter,
                                             @RequestParam(defaultValue = "") String q,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to)
            throws IOException {
        UserSession s = sessionSupport.require(sessionId);
        List<RequestView> rows = dashboard.list(s, filter, from, to).stream()
                .filter(r -> export.matchesQuery(r, q))
                .toList();
        byte[] body = export.toXlsx(rows, filter, q, dashboard.me(s), from, to);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(export.fileName(filter, from, to), StandardCharsets.UTF_8)   // RFC 5987 — 한글 파일명
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @GetMapping("/requests/{reqNo}")
    public DetailResponse detail(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.detail(s, reqNo);
    }

    /**
     * 요청 분석 노트 — SME 가 적는 요구사항 정의서.
     * 읽기는 조회 범위 안이면 누구나, **쓰기는 그 법인의 SME 만**(서비스에서 검사).
     * 본문은 HTML 이 아니라 **Quill Delta(JSON)** 다.
     */
    @GetMapping("/requests/{reqNo}/note")
    public NoteResponse note(@RequestHeader(value = "X-Session", required = false) String sessionId,
                        @PathVariable String reqNo) {
        UserSession s = sessionSupport.require(sessionId);
        return notes.get(s, reqNo);
    }

    /** 저장 — `mode=DRAFT`(임시저장) / `PUBLISH`(공유). 공유일 때만 이력이 한 행 쌓인다. */
    @PutMapping("/requests/{reqNo}/note")
    public NoteResponse saveNote(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo,
                            @RequestBody NoteSaveRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        return notes.save(s, reqNo, req);
    }

    /** 초안 폐기 — 편집 상태에서 빠져나오는 유일한 문. **공유본은 건드리지 않는다.** */
    @DeleteMapping("/requests/{reqNo}/note/draft")
    public NoteResponse discardDraft(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo) {
        UserSession s = sessionSupport.require(sessionId);
        return notes.discardDraft(s, reqNo);
    }

    /** 공유 이력 목록 — 팝업 왼쪽. 본문은 담지 않는다. */
    @GetMapping("/requests/{reqNo}/note/revisions")
    public List<NoteRevisionView> revisions(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo) {
        UserSession s = sessionSupport.require(sessionId);
        return notes.revisions(s, reqNo);
    }

    /** 공유 이력 한 건 — 팝업 오른쪽(읽기 전용). */
    @GetMapping("/requests/{reqNo}/note/revisions/{seq}")
    public NoteRevisionDetailView revision(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo, @PathVariable int seq) {
        UserSession s = sessionSupport.require(sessionId);
        return notes.revision(s, reqNo, seq);
    }

    /**
     * 요청건 코멘트 — <b>SME↔리더 / SME↔SDE</b> 두 축.
     * ⚠️ <b>내가 볼 수 있는 채널만 실려 나간다</b>(서비스에서 거른다). 화면에서 감추는 방식이면
     * 개발자도구로 그대로 보인다 — 분석 노트 초안과 같은 규칙이다.
     */
    @GetMapping("/requests/{reqNo}/comments")
    public CommentsResponse comments(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo) {
        UserSession s = sessionSupport.require(sessionId);
        return comments.list(s, reqNo);
    }

    /** 한 줄 남긴다. **수정·삭제는 없다**(append-only). 안 보이는 채널이면 403. */
    @PostMapping("/requests/{reqNo}/comments")
    public CommentsResponse writeComment(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo,
                            @RequestBody CommentWriteRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        return comments.write(s, reqNo, req);
    }

    /**
     * 탭을 연 순간을 '읽음' 으로 찍는다 — 목록의 `새 댓글` 배지가 여기서 꺼진다.
     * ⚠️ <b>조회(GET)가 아니라 이 호출이 찍는다.</b> 두 채널이 한 화면에 있어도 사람이 보는 것은
     * 열어 둔 탭 하나뿐이라, 조회에서 둘 다 찍으면 안 본 쪽 배지까지 같이 꺼진다.
     */
    @PostMapping("/requests/{reqNo}/comments/read")
    public void readComments(@RequestHeader(value = "X-Session", required = false) String sessionId,
                            @PathVariable String reqNo,
                            @RequestParam String channel) {
        UserSession s = sessionSupport.require(sessionId);
        comments.markRead(s, reqNo, channel);
    }

    @GetMapping("/stats")
    public StatsResponse stats(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.stats(s);
    }

    @GetMapping("/timeline")
    public List<TimelineView> timeline(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                      @RequestParam(defaultValue = "8") int limit) {
        UserSession s = sessionSupport.require(sessionId);
        return dashboard.timeline(s, limit);
    }
}
