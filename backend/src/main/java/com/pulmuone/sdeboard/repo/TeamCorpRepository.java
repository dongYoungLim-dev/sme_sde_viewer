package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.TeamCorp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamCorpRepository extends JpaRepository<TeamCorp, Long> {
    List<TeamCorp> findByTeam(String team);
    /** 법인명은 전역 유일 — "이미 다른 팀이 담당 중인가" 를 이걸로 판정한다. */
    Optional<TeamCorp> findByCorpNm(String corpNm);
}
