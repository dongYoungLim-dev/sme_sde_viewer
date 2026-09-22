package com.pulmuone.sdeboard.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SDE 조직 설정 — 팀 목록과 담당 차수.
 *
 * <p>ITSM 이 갖지 않은 값이라 우리가 정의한다. 조직이 바뀌면 **yml 만 고친다**
 * (`itsm.flow` 와 같은 방식 — 코드에 상수로 박으면 조직 개편 때 배포가 필요해진다).
 */
@Component
@ConfigurationProperties(prefix = "sde")
@Getter
@Setter
public class SdeProperties {

    /** 가입 시 고를 수 있는 SDE 팀. 가입자에게서 관측된 팀명과 합쳐 후보로 보여준다. */
    private List<String> teams = List.of("SDE1", "SDE2", "SDE3", "AD");

    /**
     * 법인 <b>후보</b> 목록 (사용자 확인 2026-09-09).
     *
     * <p>⚠️ 이건 <b>인력풀 표에 세울 줄이 아니라 고를 수 있는 후보</b>다.
     * 전부 세우면 관련 없는 법인이 빈 줄로 자리만 차지한다 — <b>담당 법인은 팀마다 다르다.</b>
     * 리더가 여기서 골라 자기 팀 표에 추가한다({@code team_corp}).
     *
     * <p>⚠️ 조회·차수 배지가 <b>법인명 문자열 매칭</b>이라(ITSM 이 법인코드를 주지 않는다)
     * 여기 값이 ITSM 표기와 다르면 <b>같은 법인이 두 줄</b>로 갈라진다. 관측값과 다른 항목은
     * 화면이 `미관측` 으로 표시해 리더가 알아채게 한다.
     */
    private List<String> corps = List.of(
            "샘물", "식품", "엑소", "올가홀푸드", "푸드머스", "풀무원건강생활",
            "풀무원다논", "풀무원푸드앤컬처", "풀무원헬스케어", "NOS", "메타넷");

    /**
     * 시스템 <b>후보</b> 목록 (사용자 전달 2026-09-10 `UR-260910-1`).
     *
     * <p>⚠️ {@code corps} 와 마찬가지로 <b>표에 세울 줄이 아니라 고를 수 있는 후보</b>다.
     * 리더가 여기서 골라 <b>법인과 짝지어</b> 표에 추가한다({@code team_system}).
     *
     * <p>⚠️ <b>어느 법인에 어느 시스템이 있는지는 여기 적지 않는다.</b> 사용자도 아직 모른다고 했다 —
     * 우리가 추측해 귀속을 박아 두면 틀린 짝이 조용히 굳는다. 짝은 리더가 화면에서 만든다.
     *
     * <p>⚠️ 목록의 표기를 <b>받은 그대로</b> 둔다(`PHI DS - 데몬`, `HITOK Mobile(웹 리소스)` …).
     * 다듬으면 리더가 찾던 이름이 목록에 없다.
     * `FNC시스템 / 하루` 는 원문에 <i>"시스템 모듈별로 관리"</i> 라고 적혀 있다 — 모듈마다 담당이
     * 갈리는 것으로 확인되면 줄을 쪼갠다(예: `하루 - 급여`). 지금은 한 줄로 둔다.
     */
    private List<String> systems = List.of(
            "성과관리(GCfS)", "지식작업자(AM)", "중계서버(Shiftee, 멀티캠퍼스)", "PRIS", "샘물FIS",
            "PHI DS", "PHI DS - 데몬", "TOKTOK (푸드머스)", "HITOK(Mobile API Server 포함)",
            "HITOK Mobile(웹 리소스)", "원더풀 (Cloud)", "FNC시스템 / 하루", "PQMS",
            "물류(TRMS)", "물류(RPMS)", "FNC 인사", "MIS", "FNC 홈페이지");

    /**
     * 법인당 담당 차수의 최대값. **1~5차**(2026-09-09 `UR-260909-1` 로 4→5).
     *
     * <p>차수의 <b>의미</b>는 바뀌지 않았다 — "5차 담당자가 추가된 것"뿐이다(사용자 확인 2026-09-09).
     * 그래서 "법인당 차수 1명" 검사도 그대로다.
     *
     * <p>⚠️ 이 기본값을 올리면 {@code SdePoolTest.차수는_1부터_설정값까지만_받는다} 의
     * '잘못된 값' 배열도 같이 올려야 한다 — 그 테스트는 <b>기본값</b>으로 경계를 검사한다.
     */
    private int tiers = 5;
}
