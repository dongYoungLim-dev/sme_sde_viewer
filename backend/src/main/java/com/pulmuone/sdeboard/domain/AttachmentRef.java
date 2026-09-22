package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 첨부 메타(ITSM 원격 참조). 목록 API에는 첨부 정보가 없어 아직 채워지지 않는다.
 * 상세 API에서 첨부 필드가 확인되면 동기화 대상에 추가한다.
 */
@Entity
@Table(name = "attachment_ref")
@Getter @Setter @NoArgsConstructor
public class AttachmentRef {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    @Column(name = "itsm_file_id", length = 128) private String itsmFileId;
    @Column(name = "file_name", length = 500)    private String fileName;
    @Column(name = "file_size")                  private Long fileSize;
    @Column(name = "content_type", length = 100) private String contentType;
    @Column(name = "download_ref", length = 1000) private String downloadRef;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;
}
