package com.pulmuone.sdeboard.infra;

import com.pulmuone.sdeboard.domain.AppTime;

import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.SdeAssignment;
import com.pulmuone.sdeboard.domain.TeamCorp;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.SdeAssignmentRepository;
import com.pulmuone.sdeboard.repo.TeamCorpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 기동 시 1회 — **이미 있는 배정에서 팀별 담당 법인을 복구한다.**
 *
 * <p>2026-09-09 이전에는 리더 화면이 <b>관측된 법인을 전부 자동으로</b> 세웠기 때문에
 * "어느 팀이 어느 법인을 담당하는가" 가 어디에도 저장되지 않았다. 그 자동 추가를 걷어내는 순간
 * <b>이미 배정해 둔 법인이 표에서 통째로 사라진다</b> — 데이터는 남아 있는데 볼 방법이 없어진다.
 *
 * <p>그래서 {@code sde_assignment} 을 읽어 <b>배정된 사람의 팀</b>으로 {@code team_corp} 를 채운다.
 * 이미 있는 줄은 건드리지 않으므로 몇 번을 돌려도 같은 결과다.
 */
@Component
@Order(2)                       // SchemaMigration(0) 이 표를 만든 뒤
@RequiredArgsConstructor
@Slf4j
public class TeamCorpBackfill implements ApplicationRunner {

    private final SdeAssignmentRepository poolRepo;
    private final TeamCorpRepository corpRepo;
    private final AppUserRepository userRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (poolRepo.count() == 0) return;

        Map<Long, String> teamOf = new HashMap<>();
        userRepo.findAll().forEach(u -> teamOf.put(u.getId(), u.getTeam()));

        int added = 0;
        for (SdeAssignment a : poolRepo.findAll()) {
            String team = teamOf.get(a.getUserId());
            if (team == null || team.isBlank()) continue;              // 팀을 모르면 추측하지 않는다
            if (corpRepo.findByCorpNm(a.getCorpNm()).isPresent()) continue;

            TeamCorp tc = new TeamCorp();
            tc.setTeam(team);
            tc.setCorpNm(a.getCorpNm());
            tc.setAddedBy(a.getUpdatedBy());                           // 그 배정을 넣은 리더
            tc.setAddedAt(a.getUpdatedAt() == null ? AppTime.now() : a.getUpdatedAt());
            corpRepo.save(tc);
            added++;
            log.info("담당 법인 백필 team={} corp={}", team, a.getCorpNm());
        }
        if (added > 0) log.info("팀별 담당 법인 백필 완료 — {}건", added);
    }
}
