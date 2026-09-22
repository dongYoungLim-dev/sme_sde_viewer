package com.pulmuone.sdeboard.web;

import com.pulmuone.sdeboard.web.dto.PoolDtos.*;

import com.pulmuone.sdeboard.security.SessionSupport;
import com.pulmuone.sdeboard.security.UserSession;
import com.pulmuone.sdeboard.service.SdePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * SDE 인력풀 — 법인 · 시스템 × 차수 배정표.
 * 읽기는 역할별 범위대로, **쓰기는 SDE 리더만**(서비스에서 검사한다).
 */
@RestController
@RequestMapping("/api/sde")
@RequiredArgsConstructor
public class SdePoolController {

    private final SdePoolService pool;
    private final SessionSupport sessionSupport;

    @GetMapping("/pool")
    public PoolResponse pool(@RequestHeader(value = "X-Session", required = false) String sessionId) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.pool(s);
    }

/** **시스템** 차수 배정 — `userId` 가 null 이면 그 칸을 비운다. 응답은 갱신된 표 전체다(화면이 새로 그리기 쉽게). */
    @PutMapping("/pool")
    public PoolResponse assign(@RequestHeader(value = "X-Session", required = false) String sessionId,
                          @RequestBody PoolAssignRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.assign(s, req);
    }

    /**
     * 법인담당SDE 명단에 한 명 넣기 — 리더만. <b>차수가 없다</b>(사용자 2026-09-10).
     * 인원 제한도 없다. 막는 것은 같은 사람이 두 번 들어가는 것뿐이다.
     */
    @PostMapping("/pool/leads")
    public PoolResponse addLead(@RequestHeader(value = "X-Session", required = false) String sessionId,
                           @RequestBody PoolLeadRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.addLead(s, req);
    }

    /** 법인담당SDE 명단에서 한 명 빼기 — 리더만. */
    @DeleteMapping("/pool/leads")
    public PoolResponse removeLead(@RequestHeader(value = "X-Session", required = false) String sessionId,
                              @RequestParam String corpNm, @RequestParam Long userId) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.removeLead(s, new PoolLeadRequest(corpNm, userId));
    }

    /**
     * 표에 줄 추가 — 리더만, 자기 팀 표에. 법인만 보내면 법인 블록, `systemNm` 까지 보내면
     * 그 아래 시스템 줄이 함께 선다(2026-09-10 `UR-260910-1`). 다른 팀이 담당 중인 법인이면 거부된다.
     */
    @PostMapping("/pool/rows")
    public PoolResponse addRow(@RequestHeader(value = "X-Session", required = false) String sessionId,
                           @RequestBody PoolRowRequest req) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.addRow(s, req);
    }

    /**
     * 줄 내리기 — 리더만. `systemNm` 이 있으면 그 시스템 줄만, 없으면 법인 전체.
     * **배정·명단이 남아 있으면 거부**한다(지우면 복구할 곳이 없다).
     *
     * <p>법인·시스템명을 경로가 아니라 <b>쿼리</b>로 받는다 — `FNC시스템 / 하루` 처럼
     * 이름에 `/` 가 들어가면 경로 변수가 두 조각으로 쪼개진다.
     */
    @DeleteMapping("/pool/rows")
    public PoolResponse removeRow(@RequestHeader(value = "X-Session", required = false) String sessionId,
                              @RequestParam String corpNm,
                              @RequestParam(required = false) String systemNm) {
        UserSession s = sessionSupport.require(sessionId);
        return pool.removeRow(s, corpNm, systemNm);
    }
}
