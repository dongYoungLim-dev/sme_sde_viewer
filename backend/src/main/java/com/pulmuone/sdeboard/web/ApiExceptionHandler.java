package com.pulmuone.sdeboard.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 화면이 읽는 오류 본문 — **`{ "message": "…" }`** 하나로 통일한다.
 * 프론트(`api/client.js`)는 `body.message` 를 그대로 사용자에게 보여 준다. 상태 코드는 그대로 유지한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "요청을 처리하지 못했습니다." : e.getReason()));
    }

    /** 서블릿 컨테이너 한도(컨트롤러에 닿기 전) — 서비스의 10MB 검사보다 넉넉하게 잡혀 있어 여기까지 오면 명백히 큰 파일이다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "파일이 너무 큽니다. 건당 10MB 이하만 올릴 수 있습니다."));
    }
}
