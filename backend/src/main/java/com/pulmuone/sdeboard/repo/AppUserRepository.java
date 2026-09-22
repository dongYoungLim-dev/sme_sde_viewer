package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByLoginId(String loginId);
    Optional<AppUser> findByItsmPerId(String itsmPerId);
    List<AppUser> findByRole(String role);
    List<AppUser> findByRoleAndCorpCd(String role, String corpCd);
    List<AppUser> findByRoleAndTeam(String role, String team);
    /** 인력풀 배정 대상 — 팀의 SDE **와 리더**를 함께 뽑는다(리더도 n차 담당자가 될 수 있다, 2026-09-09). */
    List<AppUser> findByRoleInAndTeam(Collection<String> roles, String team);
    /** 팀 전체(리더 + SDE). 리더의 조회 범위가 팀원 SDE 까지 넓어지면서 필요해졌다. */
    List<AppUser> findByTeam(String team);
}
