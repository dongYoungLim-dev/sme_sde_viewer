package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.web.dto.PoolDtos.*;

import com.pulmuone.sdeboard.domain.AppTime;

import com.pulmuone.sdeboard.config.SdeProperties;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.SdeAssignment;
import com.pulmuone.sdeboard.domain.TeamCorp;
import com.pulmuone.sdeboard.domain.TeamSystem;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.SdeAssignmentRepository;
import com.pulmuone.sdeboard.repo.TeamCorpRepository;
import com.pulmuone.sdeboard.repo.TeamSystemRepository;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SDE 인력풀 — **법인 · 시스템 × 차수 배정표.**
 *
 * <p>지금 엑셀로 리더가 관리하던 "1~5차 담당자" 표를 그대로 옮긴 것이다.
 * ⚠️ ITSM 에 없는 데이터라 <b>이 보드가 원본</b>이다 — 유실되면 복구할 곳이 없다.
 *
 * <p><b>2026-09-10 `UR-260910-1` — 줄 단위가 법인에서 시스템으로 내려왔다.</b>
 * 한 법인 안에서도 시스템마다 담당자가 다르다. 다만 <b>기존 배정을 옮기지 않았다</b> —
 * {@code system_nm} 이 {@code null} 인 행이 그대로 <b>법인담당SDE</b>(법인 대표 차수) 줄이 된다.
 * 시스템을 나눈 법인부터 정밀해지고, 안 나눈 법인은 예전과 똑같이 동작한다.
 * 법인↔시스템 귀속표는 <b>우리가 채우지 않는다</b>(사용자도 모른다) — 리더가 화면에서 한 줄씩 올린다.
 *
 * <p><b>누가 무엇을 보나</b> (아이디어 원문 기준)
 * <ul>
 *   <li><b>SDE 리더</b> — 자기 팀원이 어느 법인·시스템 몇 차인지. <b>편집도 리더만</b> 한다(사용자 결정 2026-09-08).
 *       2026-09-09 부터 <b>리더 자신도 배정 대상</b>이다 — 대상이 넓어진 것이지 권한이 바뀐 것은 아니다</li>
 *   <li><b>SME</b> — 자기 법인의 차수별 담당자가 누구인지 (읽기 전용)</li>
 *   <li><b>SDE</b> — <b>자기가 배정된 법인</b>의 차수 전부 (읽기 전용, 2026-09-09 `UR-260909-5`).
 *       예전에는 자기 칸 하나뿐이라 같은 법인의 2·3차가 비어 보였다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SdePoolService {

    private final SdeAssignmentRepository poolRepo;
    private final TeamCorpRepository corpRepo;
    private final TeamSystemRepository systemRepo;
    private final AppUserRepository userRepo;
    private final ItsmRequestRepository reqRepo;
    private final SdeProperties props;

    /**
     * 인력풀에 배정될 수 있는 역할.
     *
     * <p>2026-09-09(`UR-260909-1`)부터 <b>SDE 리더도 각 법인의 n차 담당자</b>가 될 수 있다 —
     * 리더가 실무를 함께 맡는 팀이 있다. SME 는 계속 대상이 아니다(요청을 내는 쪽이다).
     */
    private static final List<String> POOL_ROLES = List.of("SDE", "SDE_LEADER");

    public PoolResponse pool(UserSession session) {
        AppUser me = userRepo.findById(session.getUserId()).orElseThrow();
        boolean leader = "SDE_LEADER".equals(me.getRole());

        List<SdeAssignment> visible = visibleAssignments(me);
        Map<Long, AppUser> users = userRepo.findAll().stream()
                .collect(Collectors.toMap(AppUser::getId, u -> u));

        // 블록을 세울 법인 — 배정이 있는 법인 + (SME 면) 내 법인. 빈 블록도 보여야 "아직 미배정"이 드러난다
        Set<String> corps = new TreeSet<>(visible.stream().map(SdeAssignment::getCorpNm).toList());
        if ("SME".equals(me.getRole()) && notBlank(me.getCorpNm())) corps.add(me.getCorpNm());
        // ⚠️ 리더 화면에는 **우리 팀이 담당하기로 추가한 법인만** 세운다.
        //    예전에는 관측 법인을 전부 자동으로 세웠는데, 그러면 관련 없는 법인이 빈 줄로 자리만 차지한다
        //    (사용자 정정 2026-09-09) — 담당 법인은 팀마다 다르다.
        if (leader) corps.addAll(myTeamCorps(me));

        Map<String, List<String>> systemsOf = systemsOf(corps, visible);

        List<PoolCorpView> blocks = corps.stream()
                .map(corp -> new PoolCorpView(corp, leads(corp, visible, users),
                        systemsOf.getOrDefault(corp, List.of()).stream()
                                .map(sys -> system(corp, sys, visible, users)).toList()))
                .toList();

        // 배정 대상 목록은 리더에게만 의미가 있다(고를 수 있는 사람 = 내 팀 SDE + 나 자신)
        List<PoolMemberView> members = !leader ? List.of() : teamMembers(me).stream()
                .map(u -> new PoolMemberView(u.getId(), u.getName(), u.getLoginId(), u.getTeam(), u.getRole(),
                        "LINKED".equals(u.getLinkStatus()) && u.getLastSyncAt() != null,
                        poolRepo.findByUserId(u.getId()).stream()
                                .sorted(Comparator.comparing(SdeAssignment::getCorpNm)
                                        .thenComparing(a -> a.getSystemNm() == null ? "" : a.getSystemNm())
                                        .thenComparing(a -> a.getTier() == null ? 0 : a.getTier()))
                                .map(SdePoolService::label).toList()))
                .toList();

        return new PoolResponse(blocks, members,
                leader ? corpOptions(me, corps) : List.of(),
                leader ? props.getSystems() : List.of(),
                props.getTiers(), leader, scopeLabel(me), scopeNote(me, leader), me.getId());
    }

    /**
     * 법인담당SDE 명단 — <b>차수 없이 사람만</b>.
     * 정렬은 등록 순(id)이다. 순번이 아니라고 했으니 이름순으로 섞어 놓으면
     * 리더가 방금 넣은 사람을 목록에서 다시 찾아야 한다.
     */
    private List<PoolLeadView> leads(String corp, List<SdeAssignment> visible, Map<Long, AppUser> users) {
        return visible.stream()
                .filter(a -> a.getCorpNm().equals(corp) && trim(a.getSystemNm()) == null)
                .sorted(Comparator.comparing(SdeAssignment::getId, Comparator.nullsLast(Long::compareTo)))
                .map(a -> {
                    AppUser u = users.get(a.getUserId());
                    AppUser by = a.getUpdatedBy() == null ? null : users.get(a.getUpdatedBy());
                    return new PoolLeadView(a.getId(), a.getUserId(),
                            u == null ? "(탈퇴)" : u.getName(), u == null ? null : u.getLoginId(),
                            u == null ? null : u.getTeam(), a.getUpdatedAt(),
                            by == null ? null : by.getName());
                })
                .toList();
    }

    /**
     * <b>시스템</b> 차수 배정. `userId == null` 이면 그 칸을 비운다.
     * ⚠️ **리더만** 쓸 수 있고, **자기 팀 SDE 만** 배정할 수 있다.
     *
     * <p>⚠️ 법인에는 이 경로를 쓸 수 없다 — <b>법인담당SDE 는 차수가 없다</b>(사용자 2026-09-10).
     * 법인 명단은 {@link #addLead}/{@link #removeLead} 로 다룬다.
     */
    @Transactional
    public PoolResponse assign(UserSession session, PoolAssignRequest req) {
        AppUser me = requireLeader(session, "배정은 SDE 리더만 변경할 수 있습니다.");

        String corp = trim(req.corpNm());
        if (corp == null) throw bad("법인을 선택하세요.");
        String system = trim(req.systemNm());
        if (system == null) throw bad("시스템을 선택하세요 — 법인담당SDE 는 차수가 없습니다.");
        if (req.tier() == null || req.tier() < 1 || req.tier() > props.getTiers())
            throw bad("차수는 1~" + props.getTiers() + " 사이여야 합니다.");
        // 표에 없는 시스템에 배정하면 **어느 화면에도 안 보이는 배정**이 생긴다 — 줄이 없으니 칸도 없다.
        if (systemRepo.findByCorpNmAndSystemNm(corp, system).isEmpty())
            throw bad("표에 없는 시스템입니다. 먼저 시스템 줄을 추가해 주세요.");

        SdeAssignment existing = cellOf(corp, system, req.tier());

        if (req.userId() == null) {                    // 칸 비우기
            if (existing != null) {
                poolRepo.delete(existing);
                log.info("인력풀 해제 {} {}차 by={}", label(corp, system), req.tier(), me.getLoginId());
            }
            return pool(session);
        }

        AppUser target = requireTeamMember(me, req.userId());

        // '한 칸에 한 명' 은 DB 제약이 아니라 여기서 지킨다 —
        // 차수의 의미(순번 vs 업무분담)가 확정되면 이 한 곳만 고치면 된다.
        SdeAssignment a = existing != null ? existing : new SdeAssignment();
        a.setCorpNm(corp);
        a.setSystemNm(system);
        a.setTier(req.tier());
        a.setUserId(target.getId());
        a.setUpdatedBy(me.getId());
        a.setUpdatedAt(AppTime.now());
        poolRepo.save(a);
        log.info("인력풀 배정 {} {}차 → {} by={}", label(corp, system), req.tier(), target.getLoginId(), me.getLoginId());
        return pool(session);
    }

    /**
     * 법인담당SDE 명단에 한 명 넣기 — <b>차수를 붙이지 않는다.</b>
     *
     * <p>인원 제한이 없다(사용자 2026-09-10: "5명만 등록된다는 보장도 없다"). 막는 것은 <b>중복</b> 하나뿐이다 —
     * 같은 사람이 두 번 들어가면 명단이 조용히 늘어나고, 지울 때 어느 쪽이 지워졌는지 알 수 없다.
     */
    @Transactional
    public PoolResponse addLead(UserSession session, PoolLeadRequest req) {
        AppUser me = requireLeader(session, "법인담당SDE 는 SDE 리더만 바꿀 수 있습니다.");
        String corp = trim(req.corpNm());
        if (corp == null) throw bad("법인을 선택하세요.");
        if (req.userId() == null) throw bad("담당자를 선택하세요.");
        requireMyCorp(me, corp);
        AppUser target = requireTeamMember(me, req.userId());

        boolean dup = assignmentsOf(corp, null).stream()
                .anyMatch(a -> Objects.equals(a.getUserId(), target.getId()));
        if (dup) throw bad(target.getName() + " 님은 이미 이 법인의 담당입니다.");

        SdeAssignment a = new SdeAssignment();
        a.setCorpNm(corp);
        a.setSystemNm(null);
        a.setTier(null);                 // ⚠️ 법인 담당에는 차수가 없다
        a.setUserId(target.getId());
        a.setUpdatedBy(me.getId());
        a.setUpdatedAt(AppTime.now());
        poolRepo.save(a);
        log.info("법인담당SDE 추가 corp={} → {} by={}", corp, target.getLoginId(), me.getLoginId());
        return pool(session);
    }

    /** 법인담당SDE 명단에서 한 명 빼기. */
    @Transactional
    public PoolResponse removeLead(UserSession session, PoolLeadRequest req) {
        AppUser me = requireLeader(session, "법인담당SDE 는 SDE 리더만 바꿀 수 있습니다.");
        String corp = trim(req.corpNm());
        if (corp == null || req.userId() == null) throw bad("법인과 담당자를 지정하세요.");
        requireMyCorp(me, corp);

        assignmentsOf(corp, null).stream()
                .filter(a -> Objects.equals(a.getUserId(), req.userId()))
                .findFirst()
                .ifPresent(a -> {
                    poolRepo.delete(a);
                    log.info("법인담당SDE 해제 corp={} user={} by={}", corp, req.userId(), me.getLoginId());
                });
        return pool(session);
    }

    /**
     * 표에 줄 추가 — **리더만**, 자기 팀 표에. 법인만 고르면 <b>법인담당SDE</b> 줄, 시스템까지 고르면
     * 그 아래 <b>시스템</b> 줄이 함께 선다(2026-09-10 `UR-260910-1`).
     *
     * <p>⚠️ 다른 팀이 이미 담당 중인 법인이면 <b>거부한다.</b> 조용히 공유시키면 두 리더의 표에 같은 칸이 뜨는데
     * 상대 팀 사람은 배정할 수 없어("다른 팀의 담당자는 배정할 수 없습니다") <i>"내 화면에 있는데 왜 못 고치지"</i> 가 된다.
     * "한 법인은 한 팀"(사용자 2026-09-09)이 틀렸다면 이 거부 메시지로 드러난다.
     */
    @Transactional
    public PoolResponse addRow(UserSession session, PoolRowRequest req) {
        AppUser me = requireLeader(session);
        String corp = trim(req.corpNm());
        if (corp == null) throw bad("법인을 선택하거나 입력하세요.");
        String system = trim(req.systemNm());

        TeamCorp tc = corpRepo.findByCorpNm(corp).orElse(null);
        if (tc != null && !Objects.equals(tc.getTeam(), me.getTeam()))
            throw bad("이미 " + tc.getTeam() + " 팀이 담당 중인 법인입니다.");
        if (tc == null) {
            // 시스템만 고르고 법인이 아직 표에 없어도 한 번에 세운다 — 두 번 나눠 누르게 할 이유가 없다
            TeamCorp created = new TeamCorp();
            created.setTeam(me.getTeam());
            created.setCorpNm(corp);
            created.setAddedBy(me.getId());
            created.setAddedAt(AppTime.now());
            corpRepo.save(created);
            log.info("담당 법인 추가 team={} corp={} by={}", me.getTeam(), corp, me.getLoginId());
        }

        if (system != null && systemRepo.findByCorpNmAndSystemNm(corp, system).isEmpty()) {
            TeamSystem ts = new TeamSystem();
            ts.setCorpNm(corp);
            ts.setSystemNm(system);
            ts.setAddedBy(me.getId());
            ts.setAddedAt(AppTime.now());
            systemRepo.save(ts);
            log.info("담당 시스템 추가 corp={} system={} by={}", corp, system, me.getLoginId());
        }
        return pool(session);
    }

    /**
     * 줄 내리기 — **리더만**, 자기 팀 표에서만. `systemNm` 이 있으면 그 시스템 줄만, 없으면 법인 전체.
     *
     * <p>⚠️ <b>배정이 남아 있으면 거부한다.</b> 한 번에 최대 {@code tiers} 칸이 사라지는데
     * {@code sde_assignment} 은 ITSM 어디에도 없는 데이터라 지우면 복구할 곳이 없다.
     * 먼저 칸을 비우게 하면 "무엇을 지우는지" 를 사람이 보고 지운다.
     * 같은 이유로 <b>시스템 줄이 남은 법인</b>도 통째로 내리지 못한다.
     */
    @Transactional
    public PoolResponse removeRow(UserSession session, String corpNm, String systemNm) {
        AppUser me = requireLeader(session);
        String corp = trim(corpNm);
        String system = trim(systemNm);
        TeamCorp tc = corp == null ? null : corpRepo.findByCorpNm(corp).orElse(null);
        if (tc == null) throw bad("표에 없는 법인입니다.");
        if (!Objects.equals(tc.getTeam(), me.getTeam()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 팀이 담당하는 법인은 내릴 수 없습니다.");

        if (system != null) {
            TeamSystem ts = systemRepo.findByCorpNmAndSystemNm(corp, system)
                    .orElseThrow(() -> bad("표에 없는 시스템입니다."));
            requireEmpty(assignmentsOf(corp, system).size());
            systemRepo.delete(ts);
            log.info("담당 시스템 내림 corp={} system={} by={}", corp, system, me.getLoginId());
            return pool(session);
        }

        List<TeamSystem> systems = systemRepo.findByCorpNm(corp);
        if (!systems.isEmpty())
            throw bad("이 법인 아래 시스템 " + systems.size() + "개가 남아 있습니다. 먼저 시스템 줄을 내려 주세요.");
        int leads = assignmentsOf(corp, null).size();
        if (leads > 0)
            throw bad("법인담당SDE 가 " + leads + "명 있습니다. 먼저 명단을 비운 뒤 내려 주세요.");
        requireEmpty(poolRepo.findByCorpNm(corp).size());

        corpRepo.delete(tc);
        log.info("담당 법인 내림 team={} corp={} by={}", me.getTeam(), corp, me.getLoginId());
        return pool(session);
    }

    // ── 내부

    private void requireEmpty(int assigned) {
        if (assigned > 0)
            throw bad("배정된 담당자가 " + assigned + "명 있습니다. 먼저 칸을 비운 뒤 내려 주세요.");
    }

    private AppUser requireLeader(UserSession session) {
        return requireLeader(session, "표의 줄은 SDE 리더만 바꿀 수 있습니다.");
    }

    private AppUser requireLeader(UserSession session, String denied) {
        AppUser me = userRepo.findById(session.getUserId()).orElseThrow();
        if (!"SDE_LEADER".equals(me.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, denied);
        if (!notBlank(me.getTeam()))
            throw bad("소속 팀이 없어 담당 법인을 관리할 수 없습니다.");
        return me;
    }

    /**
     * 배정할 수 있는 사람인가 — <b>내 팀의 SDE 또는 리더</b>.
     * 시스템 차수와 법인 명단이 같은 규칙을 쓰도록 한 곳에 둔다. 두 곳에 적으면 한쪽만 느슨해진다.
     */
    private AppUser requireTeamMember(AppUser me, Long userId) {
        AppUser target = userRepo.findById(userId).orElseThrow(() -> bad("없는 사용자입니다."));
        // 리더 자신도 대상이다(2026-09-09). SME 는 여전히 아니다 — 요청을 내는 쪽이라 담당이 없다.
        if (!POOL_ROLES.contains(target.getRole()))
            throw bad("SDE 또는 SDE 리더로 가입한 사용자만 배정할 수 있습니다.");
        if (!Objects.equals(target.getTeam(), me.getTeam()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 팀의 담당자는 배정할 수 없습니다.");
        return target;
    }

    /** 우리 팀이 담당하는 법인인가. "한 법인은 한 팀" 이 여기서도 지켜져야 남의 표를 못 고친다. */
    private void requireMyCorp(AppUser me, String corp) {
        TeamCorp tc = corpRepo.findByCorpNm(corp).orElseThrow(() -> bad("표에 없는 법인입니다."));
        if (!Objects.equals(tc.getTeam(), me.getTeam()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 팀이 담당하는 법인은 바꿀 수 없습니다.");
    }

    /** 우리 팀이 담당하기로 추가한 법인. */
    private List<String> myTeamCorps(AppUser leader) {
        if (!notBlank(leader.getTeam())) return List.of();
        return corpRepo.findByTeam(leader.getTeam()).stream().map(TeamCorp::getCorpNm).toList();
    }

    /**
     * 법인마다 세울 시스템 줄.
     *
     * <p>{@code team_system} 이 원본이지만 <b>배정에 실려 온 시스템도 합친다</b> — 줄을 내리는 길과
     * 배정을 지우는 길이 따로라 어느 한쪽만 남는 순간이 생길 수 있는데, 그때 배정이 보이지 않으면
     * <b>아무도 지울 수 없는 유령 배정</b>이 된다.
     */
    private Map<String, List<String>> systemsOf(Set<String> corps, List<SdeAssignment> visible) {
        Map<String, SortedSet<String>> map = new HashMap<>();
        if (!corps.isEmpty())
            for (TeamSystem ts : systemRepo.findByCorpNmIn(corps))
                map.computeIfAbsent(ts.getCorpNm(), k -> new TreeSet<>()).add(ts.getSystemNm());
        for (SdeAssignment a : visible)
            if (notBlank(a.getSystemNm()))
                map.computeIfAbsent(a.getCorpNm(), k -> new TreeSet<>()).add(a.getSystemNm());
        return map.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private PoolSystemView system(String corp, String system, List<SdeAssignment> visible, Map<Long, AppUser> users) {
        List<PoolCellView> cells = new ArrayList<>();
        int filled = 0;
        for (int t = 1; t <= props.getTiers(); t++) {
            SdeAssignment a = find(visible, corp, system, t);
            if (a == null) { cells.add(new PoolCellView(null, t, null, null, null, null, null, null)); continue; }
            AppUser u = users.get(a.getUserId());
            AppUser by = a.getUpdatedBy() == null ? null : users.get(a.getUpdatedBy());
            cells.add(new PoolCellView(a.getId(), t, a.getUserId(),
                    u == null ? "(탈퇴)" : u.getName(), u == null ? null : u.getLoginId(),
                    u == null ? null : u.getTeam(), a.getUpdatedAt(), by == null ? null : by.getName()));
            filled++;
        }
        return new PoolSystemView(system, cells, filled);
    }

    /**
     * 추가 셀렉트의 법인 후보 — **yml 목록 ∪ 관측값 ∪ 내 표의 법인**.
     *
     * <p>관측값을 후보에 함께 넣는 이유: 새 법인 요청이 들어왔는데 후보에 없으면 아무도 모른다.
     * 자동으로 줄을 세우지는 않되(그게 "빈 줄이 자리만 차지" 하는 원인이었다) <b>놓치지도 않게</b> 한다.
     *
     * <p>⚠️ 2026-09-10 부터 <b>이미 표에 있는 법인도 후보에 남는다</b>({@code inTable=true}).
     * 그 법인 아래 시스템 줄을 추가하려면 법인을 다시 골라야 하기 때문이다.
     * <p>⚠️ {@code takenByTeam} 은 <b>남의 팀일 때만</b> 채운다 — 내 팀 법인에 붙으면 화면이 자기 법인을 잠근다.
     */
    private List<PoolCorpOptionView> corpOptions(AppUser me, Set<String> alreadyShown) {
        Set<String> observed = new HashSet<>(observedCorps());
        Map<String, String> takenBy = corpRepo.findAll().stream()
                .collect(Collectors.toMap(TeamCorp::getCorpNm, TeamCorp::getTeam, (a, b) -> a));

        Set<String> candidates = new LinkedHashSet<>(props.getCorps());
        candidates.addAll(observed);
        candidates.addAll(alreadyShown);
        return candidates.stream()
                .map(c -> {
                    String team = takenBy.get(c);
                    boolean mine = Objects.equals(team, me.getTeam());
                    return new PoolCorpOptionView(c, observed.contains(c), mine ? null : team,
                            alreadyShown.contains(c));
                })
                .sorted(Comparator.comparing((PoolCorpOptionView o) -> o.takenByTeam() != null)
                        .thenComparing(PoolCorpOptionView::corpNm))
                .toList();
    }

    /** 역할별로 보이는 배정의 범위. */
    private List<SdeAssignment> visibleAssignments(AppUser me) {
        return switch (me.getRole()) {
            // 리더: 내 팀의 배정 전부(법인 무관) — "팀원이 어디를 맡고 있나".
            // ⚠️ teamMembers 에 **리더 자신이 들어간다** — 빠지면 자기 배정을 자기가 못 본다.
            case "SDE_LEADER" -> poolRepo.findByUserIdIn(teamMembers(me).stream().map(AppUser::getId).toList());
            // SME: 내 법인의 차수별 담당자 — 팀이 달라도 본다
            case "SME" -> notBlank(me.getCorpNm()) ? poolRepo.findByCorpNm(me.getCorpNm()) : List.of();
            // SDE: **내가 배정된 법인의 전 차수** (2026-09-09 `UR-260909-5`)
            default -> myCorpAssignments(me);
        };
    }

    /**
     * SDE 가 보는 범위 — <b>내가 배정된 법인의 차수 전부</b>.
     *
     * <p>예전에는 {@code findByUserId} 하나였다. 그러면 내 법인 줄은 서지만 <b>내 차수 칸만 차고
     * 나머지는 전부 `미배정` 으로 보였다</b> — 실제로는 다른 사람이 맡고 있는데도. 같은 법인의
     * 2·3차가 누구인지 물어보려면 결국 리더에게 다시 묻게 된다(사용자 접수 2026-09-09).
     *
     * <p>⚠️ 범위의 단위는 <b>법인</b>이다. 차수가 시스템 단위로 내려간 뒤에도 그대로다 —
     * 내가 맡은 시스템만 보이면 "이 건은 누가 아나" 를 결국 다시 물어야 한다.
     *
     * <p>범위가 새지 않는 이유: 세우는 법인은 <b>내 배정에서 나온 것뿐</b>이고,
     * 한 법인은 한 팀이라({@code team_corp.corp_nm} UNIQUE) 그 칸들은 어차피 내 팀 사람이다.
     * 배정이 하나도 없는 SDE 는 여전히 빈 화면을 본다 — 볼 근거가 없다.
     */
    private List<SdeAssignment> myCorpAssignments(AppUser me) {
        Set<String> myCorps = poolRepo.findByUserId(me.getId()).stream()
                .map(SdeAssignment::getCorpNm).filter(SdePoolService::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 빈 목록으로 IN 조회를 던지지 않는다 — 방언에 따라 `in ()` 이 되어 깨진다
        return myCorps.isEmpty() ? List.of() : poolRepo.findByCorpNmIn(myCorps);
    }

    /**
     * 배정 대상이 되는 팀 구성원 — <b>SDE + 리더(자신 포함)</b>.
     * 리더를 앞에 세운다: 고를 때 "나"를 먼저 찾게 되고, 표에서도 리더 배정이 눈에 띈다.
     */
    private List<AppUser> teamMembers(AppUser leader) {
        if (!notBlank(leader.getTeam())) return List.of();
        return userRepo.findByRoleInAndTeam(POOL_ROLES, leader.getTeam()).stream()
                .sorted(Comparator.comparing((AppUser u) -> !"SDE_LEADER".equals(u.getRole()))
                        .thenComparing(AppUser::getName, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /** ITSM 이 실제로 준 법인명. 배정 화면의 후보는 여기서 뽑아야 조회 범위와 문자열이 어긋나지 않는다. */
    private List<String> observedCorps() {
        return reqRepo.findAll().stream().map(ItsmRequest::getReqCompNm)
                .filter(SdePoolService::notBlank).distinct().sorted().toList();
    }

    /** 이 법인·시스템의 배정 전부. `system == null` 이면 **법인담당SDE 줄만** — 시스템 줄까지 세지 않는다. */
    private List<SdeAssignment> assignmentsOf(String corp, String system) {
        return poolRepo.findByCorpNm(corp).stream()
                .filter(a -> Objects.equals(trim(a.getSystemNm()), system)).toList();
    }

    /** 그 줄, 그 차수의 배정 한 칸. */
    private SdeAssignment cellOf(String corp, String system, int tier) {
        return assignmentsOf(corp, system).stream()
                .filter(a -> Objects.equals(a.getTier(), tier)).findFirst().orElse(null);
    }

    private static SdeAssignment find(List<SdeAssignment> list, String corp, String system, int tier) {
        return list.stream()
                .filter(a -> a.getCorpNm().equals(corp)
                        && Objects.equals(trim(a.getSystemNm()), system)
                        && Objects.equals(a.getTier(), tier))
                .findFirst().orElse(null);
    }

    /** 로그·목록 표기 — 시스템이 없으면 법인만. */
    private static String label(String corp, String system) {
        return system == null ? corp : corp + " / " + system;
    }

    /** 사람 옆에 붙는 "지금 맡고 있는 자리". 법인 담당은 차수가 없어 이름만 나온다. */
    private static String label(SdeAssignment a) {
        String where = label(a.getCorpNm(), trim(a.getSystemNm()));
        return a.getTier() == null ? where + " 담당" : where + " " + a.getTier() + "차";
    }

    private String scopeLabel(AppUser me) {
        return switch (me.getRole()) {
            case "SDE_LEADER" -> me.getTeam() == null ? "내 팀" : me.getTeam();
            case "SME" -> me.getCorpNm() == null ? "내 법인" : me.getCorpNm();
            default -> "내 담당 법인";
        };
    }

    private String scopeNote(AppUser me, boolean leader) {
        if (leader) return "법인마다 법인담당SDE(요청 목록의 차수 배지가 읽는 줄)와 시스템별 담당자를 따로 둡니다. "
                + "우리 팀이 담당하는 법인만 세우며, 배정은 이 보드에만 있는 데이터입니다 — ITSM 에는 없습니다.";
        if ("SME".equals(me.getRole())) return "내 법인의 차수별 담당자입니다. 배정 변경은 각 팀의 SDE 리더가 합니다.";
        return "내가 배정된 법인의 차수별 담당자입니다 — 같은 법인의 다른 차수·시스템도 함께 보입니다. "
                + "변경은 소속 팀 리더가 합니다.";
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
