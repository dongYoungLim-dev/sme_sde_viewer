package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 법인 아래 담당 시스템 — **인력풀 표에 세울 줄** (2026-09-10 `UR-260910-1`).
 *
 * <p>왜 필요한가: 차수는 원래 법인 단위였는데 <i>"이 차수가 시스템별로 설정이 되어야 한다"</i>(사용자).
 * 한 법인 안에서도 시스템마다 담당자가 다르다.
 *
 * <p>담당자가 아직 한 명도 없는 <b>빈 줄</b>도 남아 있어야 하므로 {@code sde_assignment} 만으로는
 * 표현할 수 없다 — {@link TeamCorp} 과 같은 이유다(배정이 없으면 줄도 사라져 "추가해 뒀다" 는 사실이 없어진다).
 *
 * <p>⚠️ <b>{@code team} 컬럼이 없다.</b> 팀 소유권은 {@link TeamCorp} 하나가 갖는다 —
 * 시스템 줄은 언제나 그 법인을 담당하는 팀의 것이다. 두 표에 팀을 적으면 조용히 어긋날 자리가 생긴다.
 *
 * <p>⚠️ 법인↔시스템 귀속표는 <b>우리가 만들지 않는다.</b> 어느 법인에 어느 시스템이 있는지는
 * 사용자도 아직 모른다고 했다(2026-09-10). 리더가 화면에서 법인과 시스템을 골라 한 줄씩 올린다 —
 * 추측해서 미리 채워 두면 틀린 귀속이 조용히 굳는다.
 */
@Entity
@Table(name = "team_system")
@Getter @Setter @NoArgsConstructor
public class TeamSystem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code team_corp.corp_nm} — 팀은 여기서 따라온다. */
    @Column(name = "corp_nm", nullable = false, length = 100)
    private String corpNm;

    /** 시스템명. {@code sde.systems} 후보에서 고르거나 리더가 직접 입력한다. */
    @Column(name = "system_nm", nullable = false, length = 100)
    private String systemNm;

    /** 추가한 리더. 사람이 손으로 유지하는 표라 누가 넣었는지가 남아야 한다. */
    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
