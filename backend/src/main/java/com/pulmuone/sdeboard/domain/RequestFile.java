package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * SME 가 요청건에 직접 올린 첨부 — **이 보드가 원본을 갖는 첫 바이너리 데이터**다.
 * ITSM 이 갖고 있는 첨부를 읽어 오는 {@link AttachmentRef}(`attachment_ref`)와는 **별개**다.
 *
 * <p>실제 파일은 디스크(`app.upload-dir`)에, 여기에는 메타만 둔다. 삭제는 **물리 삭제**(사용자 결정) —
 * 행과 파일을 함께 지운다. 그래서 소프트 삭제 컬럼이 없다.
 */
@Entity
@Table(name = "request_attachment")
@Getter @Setter @NoArgsConstructor
public class RequestFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "req_no", nullable = false, length = 32)
    private String reqNo;

    /** 사용자가 올린 원래 이름 — 화면 표시·다운로드 이름으로만 쓴다(경로에는 절대 쓰지 않는다). */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 디스크 상의 저장 이름(난수). 업로드 디렉터리 밑 상대 경로. */
    @Column(name = "stored_name", nullable = false, length = 200)
    private String storedName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
