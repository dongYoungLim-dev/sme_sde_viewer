package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * SDE 인력풀 배정 — **어느 법인의 어느 시스템, 몇 차 담당자인가.**
 *
 * <p>⚠️ ITSM 이 갖지 않은 데이터라 <b>이 보드가 원본을 갖는 첫 테이블</b>이다.
 * 이원화는 아니다(ITSM 이 애초에 안 가진 값). 대신 이 순간부터 **유실이 우리 문제**가 된다.
 * 지금까지는 "꺼져도 ITSM 보면 된다" 였지만 이 표는 ITSM 어디에도 없다.
 *
 * <p>유지 주체는 <b>SDE 리더</b>다(사용자 결정 2026-09-08) — 지금 엑셀로 리더가 관리하던 것을 그대로 옮긴다.
 *
 * <p><b>corp_nm 이 문자열인 이유</b>: ITSM 목록 응답에 법인코드(compCd)가 없다.
 * 미러링한 사람의 토큰 compCd 를 쓰면 <i>그 사람의</i> 법인이 박혀 틀린다.
 * 그래서 ITSM 이 주는 `reqCompNm` 문자열에 맞춘다 — 후보를 관측값에서 뽑아 주고 공백 무시로 비교한다.
 */
@Entity
@Table(name = "sde_assignment")
@Getter @Setter @NoArgsConstructor
public class SdeAssignment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** app_user.id — role=SDE 인 사용자 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 법인명. ITSM `req_comp_nm` 과 문자열로 맞춘다(코드가 없다). */
    @Column(name = "corp_nm", nullable = false, length = 100)
    private String corpNm;

    /**
     * 담당 시스템 — <b>{@code null} 이면 법인담당SDE</b>(법인 대표 차수, 2026-09-10 `UR-260910-1`).
     *
     * <p>차수는 원래 법인 단위였다. 한 법인 안에서 시스템마다 담당자가 다르다는 것이 확인돼
     * 시스템 단위로 내렸는데, <b>기존 배정을 옮기지 않았다.</b> {@code null} 로 남은 행이
     * 그대로 "법인담당SDE n차" 가 된다 — 백필도 손실도 없고, 시스템을 나눈 법인부터 정밀해진다.
     *
     * <p>⚠️ <b>목록의 차수 배지는 이 값이 {@code null} 인 행만 읽는다.</b>
     * ITSM 목록 API 가 요청의 시스템을 주지 않아(요청분류는 `업무시스템`/`PHI` 두 종뿐)
     * 들어온 요청을 시스템 줄에 갖다 붙일 열쇠가 없다(사용자 결정 2026-09-10: 배지는 법인 기본값 유지).
     * 여기를 {@code null} 대신 빈 문자열로 바꾸면 <b>배지가 조용히 사라진다.</b>
     */
    @Column(name = "system_nm", length = 100)
    private String systemNm;

    /**
     * 담당 차수 1~`sde.tiers`(기본 5 — 2026-09-09 `UR-260909-1` 로 4→5).
     * <b>{@code null} 이면 차수가 없다 = 법인담당SDE 명단</b>(사용자 2026-09-10).
     * 법인 담당자에게는 순번이 없고 인원 제한도 없다 — 차수는 시스템 줄에만 있다.
     * ⚠️ 2026-09-10 이전에 저장된 법인 행은 1~5 값을 그대로 갖고 있다. <b>화면은 그 값을 쓰지 않는다</b>
     * (지우지 않은 것은 되돌릴 수 없어서다). 새로 담는 법인 행은 {@code null} 이다.
     * ⚠️ 차수의 정확한 의미(주담당/백업 순번 vs 업무 분담)는 아직 확정되지 않았다 —
     * 그래서 "법인+시스템+차수 유일" 을 **DB 제약이 아니라 서비스에서** 검사한다. 규칙이 바뀌면 한 곳만 고치면 된다.
     */
    @Column(name = "tier")
    private Integer tier;

    /** 마지막으로 고친 사람(리더). 이 표는 사람이 손으로 유지하므로 누가 언제 고쳤는지가 중요하다. */
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
