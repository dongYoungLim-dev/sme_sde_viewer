package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 팀이 담당하는 법인 — **인력풀 표에 세울 줄**.
 *
 * <p>왜 필요한가(사용자 정정 2026-09-09): 예전에는 <b>관측된 법인을 리더 화면에 전부 자동으로</b> 세웠다.
 * 그러면 <i>"관련 없는 법인의 차수별 담당자가 비워진 상태로 공간만 차지"</i> 한다.
 * <b>팀마다 담당 법인이 다르다</b> — 한 팀의 SDE 가 모든 법인에 소속되지는 않는다.
 *
 * <p>담당자가 아직 한 명도 없는 <b>빈 줄</b>도 남아 있어야 하므로 {@code sde_assignment} 만으로는 표현할 수 없다.
 * (배정이 없으면 줄도 사라져 "추가해 뒀다" 는 사실이 없어진다)
 *
 * <p>⚠️ <b>{@code corp_nm} 은 전역 UNIQUE 다.</b> "한 법인을 두 팀이 나눠 맡는 경우는 없다"(사용자 2026-09-09).
 * 그 전제 덕에 {@code sde_assignment} 의 {@code (corp_nm, tier)} 전역 유일 규칙을 그대로 둘 수 있다.
 * 전제가 틀렸다면 <b>다른 팀이 추가하려는 순간 거부되면서 드러난다</b> — 조용히 공유시키면 아무도 모른다.
 *
 * <p>⚠️ {@code sde_assignment} 에 이어 <b>이 보드가 원본을 갖는 두 번째 표</b>다. ITSM 에 없다.
 */
@Entity
@Table(name = "team_corp")
@Getter @Setter @NoArgsConstructor
public class TeamCorp {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** app_user.team — 담당 팀 (SDE1 / SDE2 / SDE3 / AD …) */
    @Column(name = "team", nullable = false, length = 100)
    private String team;

    /** 법인명. ITSM `req_comp_nm` 과 문자열로 맞춘다(코드가 없다). 전역 유일. */
    @Column(name = "corp_nm", nullable = false, unique = true, length = 100)
    private String corpNm;

    /** 추가한 리더. 사람이 손으로 유지하는 표라 누가 넣었는지가 남아야 한다. */
    @Column(name = "added_by")
    private Long addedBy;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
