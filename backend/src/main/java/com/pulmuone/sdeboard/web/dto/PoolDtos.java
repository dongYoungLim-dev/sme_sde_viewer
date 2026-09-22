package com.pulmuone.sdeboard.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SDE 인력풀 — 법인마다 <b>법인담당SDE 명단</b> + <b>시스템별 차수 배정표</b>.
 *
 * <p>⚠️ ITSM 에 없는 데이터라 **이 보드가 원본**이다 — 화면도 그렇게 말한다.
 *
 * <p>2026-09-10 — 응답 모양이 평평한 줄 목록에서 <b>법인 블록</b>으로 바뀌었다.
 * 법인담당SDE 는 <b>차수가 없고 인원 제한도 없는 명단</b>이라(사용자) 차수 격자와 모양이 아예 다르다.
 * 한 목록에 억지로 섞으면 화면이 매번 "이 줄은 어느 쪽이냐" 를 되물어야 한다.
 */
public final class PoolDtos {

    private PoolDtos() {}

    /** 시스템 차수 한 칸. `assignmentId` 가 null 이면 아직 비어 있는 칸이다. */
    public record PoolCellView(Long assignmentId, int tier,
                               Long userId, String name, String loginId, String team,
                               LocalDateTime updatedAt, String updatedByName) {}

    /**
     * 법인담당SDE 한 명 — <b>차수가 없다.</b>
     * 순번이 아니라 명단이라 인원 제한도 없다(사용자 2026-09-10: "5명만 등록된다는 보장도 없다").
     */
    public record PoolLeadView(Long assignmentId, Long userId, String name, String loginId, String team,
                               LocalDateTime updatedAt, String updatedByName) {}

    /** 시스템 한 줄 — 1~tiers 차가 순서대로 들어온다(빈 칸도 자리를 지킨다). */
    public record PoolSystemView(String systemNm, List<PoolCellView> tiers, int filled) {}

    /** 법인 블록 — 담당 명단이 먼저, 그 아래 시스템 줄. */
    public record PoolCorpView(String corpNm, List<PoolLeadView> leads, List<PoolSystemView> systems) {}

    /**
     * 배정 대상이 될 수 있는 사람 (리더 화면의 선택 목록).
     * `role` 은 표시용이다 — 2026-09-09 부터 **리더도 대상**이라 목록에서 구분이 필요해졌다.
     * `corps` 는 그 사람이 지금 맡고 있는 자리 — 고를 때 중복·과부하를 눈으로 보라고 준다.
     */
    public record PoolMemberView(Long userId, String name, String loginId, String team, String role,
                                 boolean linked, List<String> corps) {}

    /**
     * 법인 셀렉트의 한 항목.
     *
     * <p>`observed` = ITSM 요청에서 **실제로 이 이름이 관측됐는가.** false 면 이름이 ITSM 표기와
     * 다를 수 있다는 뜻이라 화면이 `미관측` 으로 알린다 — 문자열이 어긋나면 조회가 **조용히** 안 맞는다.
     * <p>`takenByTeam` = <b>다른 팀</b>이 담당 중이면 그 팀(아니면 null). 한 법인은 한 팀이다.
     * <p>`inTable` = 이미 내 표에 세워져 있는가. 화면이 후보에서 걸러 낸다.
     */
    public record PoolCorpOptionView(String corpNm, boolean observed, String takenByTeam, boolean inTable) {}

    /**
     * 인력풀 응답.
     * `editable` = 이 사용자가 배정을 고칠 수 있는가(리더만). `scopeNote` 는 무엇이 보이는 범위인지 설명.
     * ⚠️ `systemOptions` 는 법인과 무관한 <b>전체 후보</b>다 — 어느 법인에 어느 시스템이 있는지는
     * 아무도 모른다(사용자 2026-09-10). 이미 그 법인에 세워진 시스템만 화면이 걸러 낸다.
     * `meUserId` = 보고 있는 사람 자신(`UR-260909-5` 의 `나` 배지).
     */
    public record PoolResponse(List<PoolCorpView> corps, List<PoolMemberView> members,
                               List<PoolCorpOptionView> corpOptions, List<String> systemOptions,
                               int tiers,
                               boolean editable, String scopeLabel, String scopeNote,
                               Long meUserId) {}

    /**
     * 표에 줄 추가 — 법인, 그리고 <b>선택적으로</b> 그 아래 시스템.
     * `systemNm` 이 비어 있으면 법인 블록만 세운다(화면의 `법인 추가` 가 이 경로다).
     */
    public record PoolRowRequest(String corpNm, String systemNm) {}

    /**
     * <b>시스템</b> 차수 배정. `userId` 가 null 이면 그 칸을 비운다.
     * ⚠️ `systemNm` 은 필수다 — 법인담당SDE 는 차수가 없어 이 경로를 쓰지 않는다({@link PoolLeadRequest}).
     */
    public record PoolAssignRequest(String corpNm, String systemNm, Integer tier, Long userId) {}

    /** 법인담당SDE 명단에 한 명 넣기/빼기. 차수가 없으므로 사람만 지목한다. */
    public record PoolLeadRequest(String corpNm, Long userId) {}
}
