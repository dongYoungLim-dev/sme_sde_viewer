package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.AuthDtos.*;
import com.pulmuone.sdeboard.web.dto.RequestDtos.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청목록 XLSX 내보내기 검증.
 * 여기서 지키는 것 —
 *  ① 우선순위 빈 열은 **미할당 필터일 때만** 붙는다(그게 이 기능의 목적이라 다른 범위에선 노이즈다)
 *  ② 요청번호가 **문자열 셀**이다(엑셀이 숫자로 바꿔 지수 표기로 깨지면 공유 문서로 못 쓴다)
 *  ③ 첫 줄에 **연동 기준**이 박힌다(엑셀만 받은 사람이 전체로 오인하면 안 된다)
 */
class ExportServiceTest {

    private final ExportService export = new ExportService();

    private static final MeResponse ME = new MeResponse("p_meta.hong", "홍길동", "SME", null,
            "풀무원푸드앤컬처", null, "풀무원푸드앤컬처 법인", 3, 1,
            "OK", null, LocalDateTime.now(), false, List.of());

    private static RequestView row(String reqNo, String stage) {
        return row(reqNo, stage, null);
    }

    private static RequestView row(String reqNo, String stage, LocalDateTime doneAt) {
        return new RequestView(reqNo, "발주 화면 오류 수정", "박지은(park)", "FNC", "박지은",
                null, "1000", "풀무원푸드앤컬처", "변경", "REQ", "변경요청",
                "leader1", "김리더", "sde1", "이담당",
                stage, "IN_PROGRESS", "00622", "공정처리",
                "processing", 1,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 0,
                false, false,
                true, null,
                "변경", "SAP", "보통",
                doneAt, null, false, false, 0, null, false, null, 0, false);
    }

    private Sheet read(byte[] xlsx) throws Exception {
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        return wb.getSheetAt(0);
    }

    private List<String> headers(Sheet sh) {
        Row h = sh.getRow(1);
        return java.util.stream.IntStream.range(0, h.getLastCellNum())
                .mapToObj(i -> h.getCell(i).getStringCellValue()).toList();
    }

    @Test
    void 미할당_필터에만_우선순위_빈열이_붙는다() throws Exception {
        Sheet intake = read(export.toXlsx(List.of(row("CSD260828000085", "INTAKE")), "INTAKE", "", ME));
        assertThat(headers(intake)).endsWith("우선순위");
        // 헤더만 있고 값은 비어 있어야 한다 — 현업이 채워 돌려주는 칸이다
        Cell pri = intake.getRow(2).getCell(headers(intake).size() - 1);
        assertThat(pri.getCellType()).isEqualTo(CellType.BLANK);

        Sheet all = read(export.toXlsx(List.of(row("CSD260828000085", "FINAL")), "ALL", "", ME));
        assertThat(headers(all)).doesNotContain("우선순위");
    }

    @Test
    void 요청번호는_문자열_셀이다() throws Exception {
        Sheet sh = read(export.toXlsx(List.of(row("260828000085", "FINAL")), "ALL", "", ME));
        Cell c = sh.getRow(2).getCell(1);
        assertThat(c.getCellType()).isEqualTo(CellType.STRING);
        assertThat(c.getStringCellValue()).isEqualTo("260828000085");
    }

    @Test
    void 첫줄에_연동_기준이_박힌다() throws Exception {
        Sheet sh = read(export.toXlsx(List.of(row("CSD1", "FINAL")), "ALL", "", ME));
        String notice = sh.getRow(0).getCell(0).getStringCellValue();
        assertThat(notice).contains("가입 3명 중 1명 연동");
        assertThat(notice).contains("미가입자의 요청은 포함되지 않습니다");
    }

    @Test
    void 검색어는_화면과_같은_필드를_훑는다() {
        RequestView r = row("CSD260828000085", "FINAL");
        assertThat(export.matchesQuery(r, "")).isTrue();          // 빈 검색어는 통과
        assertThat(export.matchesQuery(r, "발주")).isTrue();       // 제목
        assertThat(export.matchesQuery(r, "260828")).isTrue();     // 요청번호 부분일치
        assertThat(export.matchesQuery(r, "이담당")).isTrue();     // 담당자
        assertThat(export.matchesQuery(r, "공정처리")).isTrue();   // ITSM 원본 상태명
        assertThat(export.matchesQuery(r, "없는말")).isFalse();
    }

    @Test
    void 파일명은_범위와_날짜를_담는다() {
        assertThat(export.fileName("INTAKE", null, null)).startsWith("요청목록_미할당_").endsWith(".xlsx");
        assertThat(export.fileName("ALL", null, null)).startsWith("요청목록_전체_");
        // 완료 목록은 기간이 파일을 구분한다 — 주간보고에 여러 주치를 모아 두기 때문
        assertThat(export.fileName("COMPLETED", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)))
                .isEqualTo("작업완료목록_20260901-20260907.xlsx");
    }

    @Test
    void 완료목록에만_완료일_열이_붙는다() throws Exception {
        LocalDateTime done = LocalDateTime.of(2026, 9, 7, 14, 30);
        Sheet sh = read(export.toXlsx(List.of(row("CSD1", "FINAL", done)), "COMPLETED", "",
                ME, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
        List<String> heads = headers(sh);
        assertThat(heads).contains("완료일(관측)").doesNotContain("우선순위");
        assertThat(sh.getRow(2).getCell(heads.indexOf("완료일(관측)")).getStringCellValue())
                .isEqualTo("2026-09-07 14:30");

        assertThat(headers(read(export.toXlsx(List.of(row("CSD1", "FINAL")), "ALL", "", ME))))
                .doesNotContain("완료일(관측)");
    }

    @Test
    void 완료목록_첫줄은_관측_시각임을_밝힌다() throws Exception {
        Sheet sh = read(export.toXlsx(List.of(row("CSD1", "FINAL", LocalDateTime.now())), "COMPLETED", "",
                ME, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
        String notice = sh.getRow(0).getCell(0).getStringCellValue();
        assertThat(notice).contains("완료일 2026-09-01 ~ 2026-09-07");
        assertThat(notice).contains("완료를 처음 관측한 시각");
    }
}
