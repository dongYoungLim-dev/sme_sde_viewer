package com.pulmuone.sdeboard.adapter.itsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulmuone.sdeboard.domain.AppTime;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.ItsmFlow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * ITSM 원시 JSON → 우리 미러 엔티티 변환.
 *
 * 필드명은 운영 서버 /my-page/my-to-do-request-operation 실제 응답(2026-09-08)으로 확정했다.
 *   { "data": { "content":[ {reqNo, reqTitle, perNm, reqTypCd, reqTypNm, reqDt,
 *                            defDueDate, trfPerId, staCd, staNm, reqCompNm,
 *                            reqCatNm, workNo} ], "totalPages":.., "last":.. } }
 * 스펙이 또 바뀌면 아래 상수 배열만 고치면 된다.
 * 상태 → 버킷/진행 순번 판정은 {@link ItsmFlow}(= application.yml 의 itsm.flow) 가 전담한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ItsmResponseMapper {

    private final ObjectMapper om;
    private final ItsmFlow flow;

    /** 시간대는 AppTime 하나가 정한다 — 여기 또 두면 한쪽만 바뀐다. */
    private static final ZoneId KST = AppTime.ZONE;

    // 실제 응답 기준(첫 번째가 확정 필드, 뒤는 스펙 변경 대비 후보)
    private static final String[] F_REQNO   = {"reqNo", "requestNo"};
    private static final String[] F_TITLE   = {"reqTitle", "title", "subject"};
    private static final String[] F_REQR    = {"perNm", "reqPerNm", "requester"};
    private static final String[] F_STA_CD  = {"staCd", "statusCd"};
    private static final String[] F_STA_NM  = {"staNm", "statusNm"};
    private static final String[] F_TYP_CD  = {"reqTypCd", "typCd"};
    private static final String[] F_TYP_NM  = {"reqTypNm", "typNm"};
    private static final String[] F_TRF     = {"trfPerId", "chargerNm", "assigneeNm"};
    private static final String[] F_COMPNM  = {"reqCompNm", "compNm"};
    private static final String[] F_CATNM   = {"reqCatNm", "catNm"};
    private static final String[] F_REQDT   = {"reqDt", "requestDate", "regDt"};
    private static final String[] F_DUE     = {"defDueDate", "dueDt", "hopeDt"};
    private static final String[] F_WORKNO  = {"workNo"};
    // 아직 응답에서 확인되지 않은 필드(상세 API 확정 대기)
    private static final String[] F_BODY    = {"reqCont", "reqContent", "content", "body"};
    private static final String[] F_LEADER  = {"leaderPerId", "pmPerId"};
    private static final String[] F_LEADERN = {"leaderNm", "pmNm"};
    private static final String[] F_SDE_ID  = {"chargerId", "workerId", "assigneeId"};
    private static final String[] F_COMPCD  = {"compCd"};

    /** 목록 응답에서 요청 배열 추출. 실제 경로는 data.content. */
    public List<JsonNode> extractList(String rawJson) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode data = data(rawJson);
        JsonNode arr = firstArray(data, "content", "list", "items", "rows");
        if (arr == null && data.isArray()) arr = data;
        if (arr != null) arr.forEach(out::add);
        return out;
    }

    /** 마지막 페이지인지. data.last 가 없으면 true(1회만 조회)로 본다. */
    public boolean isLastPage(String rawJson) {
        JsonNode data = data(rawJson);
        if (data.hasNonNull("last")) return data.get("last").asBoolean(true);
        if (data.hasNonNull("totalPages") && data.hasNonNull("number"))
            return data.get("number").asInt() >= data.get("totalPages").asInt() - 1;
        return true;
    }

    public int totalElements(String rawJson) {
        JsonNode data = data(rawJson);
        return data.hasNonNull("totalElements") ? data.get("totalElements").asInt() : -1;
    }

    private JsonNode data(String rawJson) {
        try {
            return om.readTree(rawJson == null ? "{}" : rawJson).path("data");
        } catch (Exception e) {
            log.warn("ITSM 응답 파싱 실패: {}", e.getMessage());
            return om.createObjectNode();
        }
    }

    public ItsmRequest toEntity(JsonNode n, ItsmRequest existing) {
        ItsmRequest r = existing != null ? existing : new ItsmRequest();
        r.setReqNo(text(n, F_REQNO));
        r.setTitle(text(n, F_TITLE));
        r.setRequester(text(n, F_REQR));
        // ⚠️ 계정(역할)마다 응답에 이 필드가 빠질 수 있다 — 없으면 덮어쓰지 않고 기존 값을 지킨다.
        //    (SME 화면은 이 값으로 법인을 판정하므로, 한 번 null 로 덮이면 그 건이 SME 화면에서 조용히 사라진다)
        String compNm = text(n, F_COMPNM);
        if (compNm != null && !compNm.isBlank()) r.setReqCompNm(compNm);
        r.setReqCatNm(text(n, F_CATNM));
        r.setCompCd(text(n, F_COMPCD));
        r.setReqTypCd(text(n, F_TYP_CD));
        r.setReqTypNm(text(n, F_TYP_NM));

        String staCd = text(n, F_STA_CD);
        String staNm = text(n, F_STA_NM);
        r.setItsmStaCd(staCd);
        r.setItsmStaNm(staNm);
        // 보드 상태(work_status)는 ITSM 상태에서 뽑지 않는다 — 처음 보면 대기, 그다음은 스케줄·To-Do 잔존이 정한다.
        if (r.getWorkStatus() == null) r.setWorkStatus(BoardStatus.WAITING);

        // ⚠ trfPerId 는 ID가 아니라 담당자 '이름'으로 온다(예: "임동영").
        //    담당자 ID·담당 리더 필드는 목록 응답에 없으므로 상세 API 확정 전까지 이름만 채운다.
        String trf = text(n, F_TRF);
        r.setTrfPerId(trf);
        r.setAssigneeName(trf);
        r.setAssigneePerId(text(n, F_SDE_ID));
        r.setLeaderPerId(text(n, F_LEADER));
        r.setLeaderName(text(n, F_LEADERN));

        String body = text(n, F_BODY);
        if (body != null && !body.isBlank()) r.setBody(body);   // 목록엔 없음 — 상세가 채우면 유지

        Integer workNo = intOrNull(n, F_WORKNO);
        r.setWorkNo(workNo);
        // 처리 유형(서비스요청 처리 / 직접처리)은 **목록 응답만으로 알 수 없다.**
        //  · processing-service-request 는 workNo(>0) 로 '처리 작업' 한 건을 지정해 조회한다.
        //  · direct-process-detail 은 reqNo 만 받는다(작업 분할이 없는 직접처리 건).
        // 관측된 목록은 전부 workNo=0 (= 처리 작업 미생성으로 추정)이라 유형을 단정하지 않는다.
        r.setWorkType(workNo != null && workNo > 0 ? "processing" : null);

        r.setReqDt(dateTime(n, F_REQDT));
        r.setDueDate(dateTime(n, F_DUE));
        r.setRawJson(n.toString());
        r.recomputeStage(flow.seqOf(staCd, staNm), flow.assignFromSeq());
        return r;
    }

    // ---- helpers ----
    private static JsonNode firstArray(JsonNode node, String... keys) {
        for (String k : keys) if (node.path(k).isArray()) return node.get(k);
        return null;
    }
    private static String text(JsonNode n, String[] keys) {
        for (String k : keys) {
            if (n.hasNonNull(k)) {
                String v = n.get(k).asText();
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }
    private static Integer intOrNull(JsonNode n, String[] keys) {
        for (String k : keys) if (n.hasNonNull(k) && n.get(k).isNumber()) return n.get(k).asInt();
        return null;
    }
    /** ITSM 은 날짜를 epoch millis 로 준다(reqDt=1787891700000). 문자열 날짜도 방어적으로 지원. */
    private static LocalDateTime dateTime(JsonNode n, String[] keys) {
        for (String k : keys) {
            if (!n.hasNonNull(k)) continue;
            JsonNode v = n.get(k);
            if (v.isNumber()) {
                long ms = v.asLong();
                if (ms <= 0) return null;
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), KST);
            }
            String s = v.asText();
            if (s == null || s.isBlank()) continue;
            try {
                if (s.matches("\\d{13}")) return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(s)), KST);
                if (s.length() >= 19) return LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T'));
                if (s.length() >= 10) return java.time.LocalDate.parse(s.substring(0, 10)).atStartOfDay();
            } catch (Exception ignore) { }
        }
        return null;
    }
}
