package com.pulmuone.sdeboard.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 사용자 마스터 (SME / SDE_LEADER). 유일하게 우리가 직접 편집하는 데이터.
 * 로그인은 ITSM 계정으로 한다 — **ITSM 비밀번호는 저장하지 않는다**(세션 메모리 전용).
 */
@Entity
@Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** = ITSM 계정(perId) */
    @Column(name = "login_id", nullable = false, unique = true, length = 64)
    private String loginId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "itsm_per_id", length = 64)
    private String itsmPerId;

    /** ITSM 토큰 payload 의 compCd — 가입 시 자동 취득 */
    @Column(name = "itsm_comp_cd", length = 10)
    private String itsmCompCd;

    @Column(name = "role", nullable = false, length = 20)
    private String role;          // SME / SDE_LEADER / SDE

    /**
     * SDE 리더의 <b>정/부</b> 구분 — {@code MAIN}(정) / {@code SUB}(부). 리더가 아니면 null.
     *
     * <p>⚠️ <b>권한 차이가 없다</b>(사용자 확인 2026-09-09). 그래서 {@code role} 을
     * {@code SDE_LEADER_MAIN/SUB} 로 쪼개지 않는다 — {@code role} 문자열 비교가 서비스 곳곳에 있어
     * 값이 늘면 컴파일 에러 없이 <b>런타임에 조용히</b> 리더로 인식되지 않는 곳이 생긴다.
     * 권한이 같은 값은 권한 축에 올리지 않는다.
     */
    @Column(name = "leader_rank", length = 10)
    private String leaderRank;

    @Column(name = "corp_cd", length = 10)
    private String corpCd;        // SME 소속 법인코드
    @Column(name = "corp_nm", length = 100)
    private String corpNm;
    @Column(name = "team", length = 100)
    private String team;          // SDE 리더 소속 팀

    @Column(name = "link_status", nullable = false, length = 20)
    private String linkStatus = "LINKED";   // LINKED / AUTH_FAILED

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 조회 범위 키 — SME는 법인, 리더는 팀 */
    public String scopeKey() {
        return "SME".equals(role) ? ("CORP:" + safe(corpCd)) : ("TEAM:" + safe(team));
    }
    private static String safe(String s) { return s == null ? "" : s; }
}
