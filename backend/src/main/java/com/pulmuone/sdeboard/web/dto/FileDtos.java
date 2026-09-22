package com.pulmuone.sdeboard.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/** SME 첨부파일 API 메시지 (`UR-260922-1`). */
public final class FileDtos {

    private FileDtos() {}

    /** `canDelete` 는 이 사용자가 지울 수 있는가(그 요청을 볼 수 있는 SME — 올린 사람이 자리를 비워도 정정할 수 있게) — 응답에서 결정해 화면이 다시 계산하지 않게 한다. */
    public record FileView(Long id, String reqNo, String fileName, Long fileSize, String contentType,
                           Long uploadedBy, String uploadedByName, LocalDateTime uploadedAt, boolean canDelete) {}

    /** 첨부 목록 + 정책. `canUpload` 는 이 사용자가 지금 올릴 수 있는가(SME · 5개 미만 · 진행 중인 건). */
    public record FilesResponse(List<FileView> files, boolean canUpload, String uploadBlockedReason,
                                int maxFiles, long maxBytes, List<String> allowedExtensions) {}
}
