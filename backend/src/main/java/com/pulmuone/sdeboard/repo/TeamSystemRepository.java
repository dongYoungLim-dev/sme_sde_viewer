package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.TeamSystem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamSystemRepository extends JpaRepository<TeamSystem, Long> {
    List<TeamSystem> findByCorpNm(String corpNm);
    /** 표를 한 번에 그린다 — 법인마다 조회를 던지면 줄 수만큼 쿼리가 늘어난다. */
    List<TeamSystem> findByCorpNmIn(Collection<String> corpNms);
    Optional<TeamSystem> findByCorpNmAndSystemNm(String corpNm, String systemNm);
}
