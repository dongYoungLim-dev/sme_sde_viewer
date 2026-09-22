package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.service.RequestFileService;
import com.pulmuone.sdeboard.web.dto.FileDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SME 첨부파일 (`UR-260922-1`). ITSM 첨부(`/api/attachments`, 미구현)와 **별개**다.
 * 다운로드는 X-Session 헤더가 필요해 화면이 fetch → blob 으로 받는다(`<a href>` 는 헤더를 못 붙인다).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RequestFileController {

    private final RequestFileService files;
    private final SessionSupport sessionSupport;

    @GetMapping("/requests/{reqNo}/files")
    public FilesResponse list(@RequestHeader(value = "X-Session", required = false) String sessionId, @PathVariable String reqNo) {
        return files.list(sessionSupport.require(sessionId), reqNo);
    }

    @PostMapping(value = "/requests/{reqNo}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileView upload(@RequestHeader(value = "X-Session", required = false) String sessionId,
                           @PathVariable String reqNo, @RequestPart("file") MultipartFile file) {
        return files.upload(sessionSupport.require(sessionId), reqNo, file);
    }

    @GetMapping("/request-files/{id}/download")
    public ResponseEntity<ByteArrayResource> download(@RequestHeader(value = "X-Session", required = false) String sessionId,
                                                      @PathVariable Long id) {
        RequestFileService.Download d = files.download(sessionSupport.require(sessionId), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType() == null ? "application/octet-stream" : d.contentType()))
                .body(new ByteArrayResource(d.data()));
    }

    @DeleteMapping("/request-files/{id}")
    public Map<String, Object> delete(@RequestHeader(value = "X-Session", required = false) String sessionId, @PathVariable Long id) {
        files.delete(sessionSupport.require(sessionId), id);
        return Map.of("ok", true);
    }
}
