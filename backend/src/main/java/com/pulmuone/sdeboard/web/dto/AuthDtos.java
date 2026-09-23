package com.pulmuone.sdeboard.web.dto;


import java.time.LocalDateTime;
import java.util.List;

/** 인증 — 로그인은 **ITSM 계정 인증**이다. ITSM 비밀번호는 저장하지 않는다. */
public final class AuthDtos {

    private AuthDtos() {}

    /** `leaderRank` 는 **role=SDE_LEADER 일 때만** 의미가 있다(MAIN=정 / SUB=부). 서버가 그 밖의 값은 버린다. */
    public record SignupRequest(String itsmUsername, String itsmPassword, String name, String email,
                                String role, String corpNm, String team, String leaderRank) {}

    public record LoginRequest(String itsmUsername, String itsmPassword) {}

    public record LoginResponse(boolean success, String sessionId, String loginId, String name, String role,
                                String message, boolean needSignup) {}

    /** 회원가입 화면용 기존 소속 목록 (ITSM이 법인/팀 마스터를 주지 않아 가입자 값으로 채운다) */
    public record MetaResponse(List<String> corps, List<String> teams) {}

    /**
     * 마이페이지 수정 요청.
     * ⚠️ **`leaderRank` 하나만 받는다.** 팀·법인은 조회 범위 그 자체라(`AppUser.scopeKey`),
     * 여기서 고칠 수 있게 하면 자기 조회 범위를 자기가 바꾸는 경로가 열린다.
     */
    public record ProfileUpdateRequest(String leaderRank) {}

    /**
     * SME 가 관리하는 법인의 담당 SDE 리더 한 명 — 정/부 전부, ITSM 동기화 여부와 무관하게 항상 나온다.
     * (`UR-260923-2`) `team_corp`(팀↔담당법인)로 찾는다 — 그 리더가 지금 ITSM 건을 들고 있는지와는 별개다.
     */
    public record LeaderContactView(Long userId, String name, String loginId, String leaderRank, boolean linked) {}

    /**
     * 로그인 사용자 정보 + 연동/커버리지 상태. `leaderRank` 는 리더가 아니면 null.
     * `assignedLeaders` 는 SME 에게만 채워진다(그 외 역할은 빈 목록) — {@link LeaderContactView} 참고.
     */
    public record MeResponse(String loginId, String name, String role, String leaderRank,
                             String corpNm, String team, String scopeLabel,
                             int scopeMembers, int scopeLinked,
                             String syncState, String syncMessage,
                             LocalDateTime lastSyncAt, boolean keepCredential,
                             List<LeaderContactView> assignedLeaders) {}
}
