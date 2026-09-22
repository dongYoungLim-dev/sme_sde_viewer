package com.pulmuone.sdeboard.domain;


import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 미러 데이터에서 표시용 파생값 계산.
 * 제목 정규식 추측 대신 ITSM 이 실제로 주는 필드(reqTypNm·reqCatNm·reqCompNm)를 쓴다.
 */
public final class RequestDerivation {
    private RequestDerivation() {}

    public static LocalDate dueDate(ItsmRequest r) {
        return r.getDueDate() == null ? null : r.getDueDate().toLocalDate();
    }

    public static boolean late(ItsmRequest r, LocalDate today) {
        LocalDate due = dueDate(r);
        return !BoardStatus.isClosed(r.getWorkStatus()) && due != null && due.isBefore(today);
    }

    public static boolean soon(ItsmRequest r, LocalDate today) {
        LocalDate due = dueDate(r);
        if (BoardStatus.isClosed(r.getWorkStatus()) || due == null) return false;
        long d = ChronoUnit.DAYS.between(today, due);
        return d >= 0 && d <= 2;
    }

    /** perNm 은 "박지은(park Ji Eun)" 형태 — 괄호 앞 한글 이름만 쓴다. */
    public static String requesterName(String perNm) {
        if (perNm == null || perNm.isBlank()) return "-";
        int i = perNm.indexOf('(');
        return (i > 0 ? perNm.substring(0, i) : perNm).trim();
    }

    /** 요청 부서 필드는 없다 — 요청 회사(reqCompNm)로 대체 표기. */
    public static String dept(ItsmRequest r) {
        return blankToDash(r.getReqCompNm());
    }

    /** 요청 유형 — reqTypNm (예: 변경 / 배포메인 상태) */
    public static String reqType(ItsmRequest r) {
        return r.getReqTypNm() == null || r.getReqTypNm().isBlank() ? "일반 요청" : r.getReqTypNm();
    }

    /** 대상 시스템 대신 ITSM 요청 분류(reqCatNm, 예: 업무시스템) */
    public static String targetSystem(ItsmRequest r) {
        return blankToDash(r.getReqCatNm());
    }

    public static String priority(ItsmRequest r, LocalDate today) {
        if (late(r, today)) return "긴급";
        if (soon(r, today)) return "높음";
        return "보통";
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
