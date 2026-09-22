package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;
import com.pulmuone.sdeboard.web.dto.RequestDtos.*;

import com.pulmuone.sdeboard.domain.AppTime;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 요청 목록 XLSX 내보내기.
 *
 * <p>목적은 "우선순위를 못 정할 때 현업에게 넘겨 정하게 하는 것"이라, 화면에서 보고 있는 그대로
 * (현재 상태 필터 + 검색어)를 내보낸다. 상태 필터가 <b>미할당(INTAKE)</b> 일 때만
 * 현업이 채워 돌려줄 <b>우선순위</b> 빈 열을 붙인다.
 *
 * <p>⚠️ 내보낸 파일은 <b>부분 데이터</b>다 — 가입·연동된 사람의 ITSM 할 일만 미러링하기 때문에,
 * 엑셀만 받아 본 사람이 전체로 오인하지 않도록 첫 줄에 연동 기준을 박아 둔다.
 */
@Service
@RequiredArgsConstructor
public class ExportService {

    /** 상태 필터 코드 → 사람이 읽는 이름. 파일명·머리글에 쓴다. */
    private static final Map<String, String> FILTER_LABEL = new LinkedHashMap<>() {{
        put("ALL", "전체");           put("OPEN", "진행중");    put("LATE", "지연");
        put("INTAKE", "미할당");      put("LEADERP", "SDE배정대기"); put("CLOSED", "할일종료");
        put("COMPLETED", "작업완료");  put("CMT", "새댓글");
        put("WAITING", "대기");       put("IN_PROGRESS", "작업중");
        put("UNTRACKED", "추적불가");  put("DONE", "작업완료");
    }};

    /** 우선순위 빈 열을 붙이는 필터. 이 기능의 목적(현업이 우선순위를 채워 돌려줌)이 성립하는 범위다. */
    public static final String PRIORITY_FILTER = "INTAKE";

    /** 완료일 열을 붙이는 필터. 완료 목록은 주간보고에 쓰이므로 완료일이 없으면 의미가 없다. */
    public static final String DONE_FILTER = "COMPLETED";

    private static final DateTimeFormatter FILE_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static String filterLabel(String filter) {
        return FILTER_LABEL.getOrDefault(filter == null ? "ALL" : filter, filter);
    }

    public static boolean withPriority(String filter) {
        return PRIORITY_FILTER.equals(filter);
    }

    public static boolean withDoneAt(String filter) {
        return DONE_FILTER.equals(filter);
    }

    public String fileName(String filter, LocalDate from, LocalDate to) {
        String period = from == null && to == null
                ? AppTime.today().format(FILE_DT)
                : (from == null ? "" : from.format(FILE_DT)) + "-" + (to == null ? "" : to.format(FILE_DT));
        return (withDoneAt(filter) ? "작업완료목록_" : "요청목록_" + filterLabel(filter) + "_") + period + ".xlsx";
    }

    /**
     * 화면 검색창과 같은 필드를 훑는다(법인·요청번호·제목·요청자·담당자·상태).
     * 프론트에서 검색어로 걸러 놓은 목록과 파일 내용이 어긋나지 않도록 서버도 같은 규칙을 쓴다.
     */
    /**
     * 검색 규칙 — **화면과 같은 필드, 같은 방식이어야 한다.**
     * 짝은 프론트의 `domain/request.js` 의 {@code SEARCH_FIELDS} 다. 한쪽만 늘리면
     * "화면엔 나오는데 엑셀엔 없다"(또는 그 반대)가 되고, 사용자는 엑셀이 틀렸다고만 말할 수 있다.
     */
    public boolean matchesQuery(RequestView r, String q) {
        if (q == null || q.isBlank()) return true;
        String n = q.trim().toLowerCase(Locale.ROOT);
        return anyContains(n, r.reqCompNm(), r.dept(), r.reqNo(), r.title(),
                r.requester(), r.requesterName(), r.assigneeName(), r.itsmStaNm());
    }

    private boolean anyContains(String needle, String... vals) {
        for (String v : vals) if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    public byte[] toXlsx(List<RequestView> rows, String filter, String q, MeResponse me) throws IOException {
        return toXlsx(rows, filter, q, me, null, null);
    }

    public byte[] toXlsx(List<RequestView> rows, String filter, String q, MeResponse me,
                         LocalDate from, LocalDate to) throws IOException {
        boolean priority = withPriority(filter);
        boolean doneCol = withDoneAt(filter);
        List<String> heads = new java.util.ArrayList<>(List.of(
                "법인", "요청번호", "요청제목", "요청자", "담당자", "ITSM 상태", "상태", "일정", "등록일", "기한", "할 일 상태"));
        if (doneCol) heads.add("완료일(관측)");
        if (priority) heads.add("우선순위");

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("요청목록");
            Styles st = new Styles(wb);

            // 0행: 이 파일이 무엇이고 어디까지 담고 있는지. 받는 사람이 전체로 오인하지 않도록.
            Row notice = sh.createRow(0);
            Cell nc = notice.createCell(0);
            nc.setCellValue(noticeText(rows.size(), filter, q, me, from, to));
            nc.setCellStyle(st.notice);
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, heads.size() - 1));
            notice.setHeightInPoints(30);

            Row head = sh.createRow(1);
            for (int i = 0; i < heads.size(); i++) {
                Cell c = head.createCell(i);
                c.setCellValue(heads.get(i));
                c.setCellStyle(st.head);
            }

            int rn = 2;
            for (RequestView r : rows) {
                Row row = sh.createRow(rn++);
                int i = 0;
                text(row, i++, nz(r.reqCompNm(), r.dept()), st.body);
                text(row, i++, r.reqNo(), st.mono);                    // 문자열 셀 — 엑셀 자동 서식 방지
                text(row, i++, r.title(), st.body);
                text(row, i++, nz(r.requesterName(), r.requester()), st.body);
                text(row, i++, assignee(r), st.body);
                text(row, i++, nz(r.itsmStaNm(), "-"), st.body);
                text(row, i++, com.pulmuone.sdeboard.domain.BoardStatus.label(r.workStatus()), st.center);
                text(row, i++, scheduleText(r), st.center);
                text(row, i++, r.reqDt() == null ? "" : r.reqDt().toString(), st.center);
                text(row, i++, dueText(r), st.center);
                text(row, i++, r.inTodo() ? "진행" : "ITSM 할 일 종료"
                        + (r.closedAt() == null ? "" : " · " + r.closedAt().format(STAMP)), st.center);
                if (doneCol)
                    text(row, i++, r.doneAt() == null ? "-" : r.doneAt().format(STAMP), st.center);
                if (priority) row.createCell(i).setCellStyle(st.input);   // 빈 칸 — 현업이 채운다
            }

            int[] widths = { 14, 20, 46, 12, 18, 18, 10, 36, 12, 18, 20, 18, 12 };
            for (int i = 0; i < heads.size(); i++) sh.setColumnWidth(i, widths[Math.min(i, widths.length - 1)] * 256);
            sh.createFreezePane(0, 2);
            if (!rows.isEmpty())
                sh.setAutoFilter(new CellRangeAddress(1, 1 + rows.size(), 0, heads.size() - 1));

            wb.write(out);
            return out.toByteArray();
        }
    }

    private String noticeText(int n, String filter, String q, MeResponse me, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        sb.append("SDE 작업현황 보드 · ")
          .append(withDoneAt(filter) ? "작업 완료 목록" : "요청목록 (" + filterLabel(filter) + ")")
          .append(' ').append(n).append("건");
        if (from != null || to != null)
            sb.append(" · 완료일 ").append(from == null ? "처음" : from).append(" ~ ").append(to == null ? "오늘" : to);
        if (q != null && !q.isBlank()) sb.append(" · 검색 \"").append(q.trim()).append('"');
        sb.append(" · 내보낸 시각 ").append(AppTime.now().format(STAMP));
        if (me != null)
            sb.append("\n※ ").append(me.scopeLabel()).append(" 범위 · 가입 ").append(me.scopeMembers())
              .append("명 중 ").append(me.scopeLinked()).append("명 연동 기준입니다 — 미가입자의 요청은 포함되지 않습니다.");
        if (withPriority(filter))
            sb.append(" '우선순위' 열을 채워 회신해 주세요.");
        if (withDoneAt(filter))
            sb.append(" ※ 완료일은 ITSM 원본이 아니라 이 보드가 완료를 처음 관측한 시각입니다"
                    + "(폴링 5분 주기 · 관측 시작 이전 완료 건은 포함되지 않습니다).");
        return sb.toString();
    }

    private String assignee(RequestView r) {
        if ("FINAL".equals(r.assignStage())) return nz(r.assigneeName(), "-");
        if ("LEADER".equals(r.assignStage())) return "SDE 배정 대기" + (r.leaderName() == null ? "" : " (" + r.leaderName() + ")");
        return "SME 확인 대기";
    }

    /** 유효한 일정이 있으면 "시작 ~ 종료 (작업자)". 없으면 "-". */
    private String scheduleText(RequestView r) {
        if (r.schedule() == null || r.schedule().start() == null) return "-";
        return r.schedule().start().format(STAMP) + " ~ " + r.schedule().end().format(STAMP)
                + (r.schedule().assigneeName() == null ? "" : " (" + r.schedule().assigneeName() + ")");
    }

    private String dueText(RequestView r) {
        if (r.dueDate() == null) return "-";
        String s = r.dueDate().toString();
        if (r.late()) return s + " (지연)";
        if (r.soon()) return s + " (임박)";
        return s;
    }

    private static String nz(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static void text(Row row, int i, String v, CellStyle style) {
        Cell c = row.createCell(i);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    /** 셀 서식 묶음. 워크북마다 새로 만들어야 한다(POI 스타일은 워크북에 종속). */
    private static final class Styles {
        final CellStyle notice, head, body, mono, center, pct, input;

        Styles(Workbook wb) {
            DataFormat fmt = wb.createDataFormat();
            Font base = wb.createFont(); base.setFontHeightInPoints((short) 10);
            Font bold = wb.createFont(); bold.setBold(true); bold.setFontHeightInPoints((short) 10);
            Font small = wb.createFont(); small.setFontHeightInPoints((short) 9); small.setItalic(true);

            notice = wb.createCellStyle();
            notice.setFont(small); notice.setWrapText(true); notice.setVerticalAlignment(VerticalAlignment.CENTER);

            head = wb.createCellStyle();
            head.setFont(bold);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(head);

            body = wb.createCellStyle(); body.setFont(base); body.setWrapText(false);
            body.setVerticalAlignment(VerticalAlignment.CENTER); border(body);

            mono = wb.createCellStyle(); mono.cloneStyleFrom(body);
            mono.setDataFormat(fmt.getFormat("@"));               // 텍스트 고정 — 요청번호가 숫자로 바뀌지 않게

            center = wb.createCellStyle(); center.cloneStyleFrom(body);
            center.setAlignment(HorizontalAlignment.CENTER);

            pct = wb.createCellStyle(); pct.cloneStyleFrom(center);
            pct.setDataFormat(fmt.getFormat("0%"));

            input = wb.createCellStyle(); input.cloneStyleFrom(center);
            input.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static void border(CellStyle s) {
            s.setBorderTop(BorderStyle.HAIR); s.setBorderBottom(BorderStyle.HAIR);
            s.setBorderLeft(BorderStyle.HAIR); s.setBorderRight(BorderStyle.HAIR);
        }
    }
}
