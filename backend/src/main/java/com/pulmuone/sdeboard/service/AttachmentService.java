package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AttachmentRef;
import com.pulmuone.sdeboard.repo.AttachmentRefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 첨부 다운로드. 브라우저는 ITSM에 직접 접근 못 하므로 반드시 백엔드를 경유한다.
 * 목록 API 응답에는 첨부 정보가 없다 → 상세 API에서 첨부 필드/다운로드 방법이 확인되면 구현한다.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRefRepository attRepo;

    public record DownloadResult(String fileName, String contentType, byte[] data) {}

    public DownloadResult download(Long id) {
        AttachmentRef a = attRepo.findById(id).orElseThrow();
        throw new UnsupportedOperationException(
                "ITSM 첨부 다운로드 API 미확정 — 상세 API 확인 후 프록시 구현 필요 (file=" + a.getFileName() + ")");
    }
}
