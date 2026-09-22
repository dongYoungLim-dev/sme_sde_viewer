package com.pulmuone.sdeboard.infra;

import com.pulmuone.sdeboard.config.ItsmProperties;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmFlow;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestOwner;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestOwnerRepository;
import com.pulmuone.sdeboard.repo.RequestStatusHistoryRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * **예전 상태 → 4단계 이관**(`UR-260922-1`)과 **완료일(`done_at`) 소급**.
 *
 * 이관 규칙이 흔들리면 화면에서는 알아챌 방법이 없다 — 완료 목록·주간보고 숫자가 조용히 달라진다.
 * 특히 "완료로 확인된 적 없이 사라진 건은 작업완료가 아니라 추적불가" 가 무너지면 주간보고가 부풀어 오른다.
 */
class StatusBackfillTest {

    private static final String CLOSED = RequestStatusHistory.CLOSED_MARK;
    private static final LocalDateTime T_DONE = LocalDateTime.of(2026, 9, 7, 14, 30);
    private static final LocalDateTime T_CLOSED = LocalDateTime.of(2026, 9, 7, 18, 0);

    private final ItsmRequestRepository reqRepo = mock(ItsmRequestRepository.class);
    private final RequestOwnerRepository ownerRepo = mock(RequestOwnerRepository.class);
    private final RequestStatusHistoryRepository histRepo = mock(RequestStatusHistoryRepository.class);
    private final StatusBackfill backfill = new StatusBackfill(reqRepo, histRepo, ownerRepo, new ItsmFlow(new ItsmProperties()));

    private static ItsmRequest req(String reqNo, String workStatus) {
        ItsmRequest r = new ItsmRequest();
        r.setReqNo(reqNo);
        r.setWorkStatus(workStatus);
        r.setLastChangedAt(T_DONE);
        r.setAssignStage("INTAKE");     // 담당자 필드가 없는 건의 파생값 — 이미 계산된 상태로 둔다
        return r;
    }

    private static RequestStatusHistory h(String toStatus, String toStaNm, LocalDateTime at) {
        RequestStatusHistory x = new RequestStatusHistory();
        x.setToStatus(toStatus);
        x.setToStaNm(toStaNm);
        x.setObservedAt(at);
        return x;
    }

    private void holds(String reqNo, boolean held) {
        when(ownerRepo.findByReqNoAndActiveTrue(reqNo)).thenReturn(held ? List.of(mock(RequestOwner.class)) : List.of());
    }

    // --- 이관 ---

    @Test
    void 예전_완료는_작업완료로_그대로_간다() {
        assertThat(backfill.migrate(req("A", "DONE"))).isEqualTo(BoardStatus.DONE);
    }

    @Test
    void 아직_누군가_들고_있는_진행중_건은_모두_대기가_된다() {
        holds("A", true);
        for (String legacy : List.of("NEW", "ANAL", "DEV", "TEST", "DEP", "HOLD"))
            assertThat(backfill.migrate(req("A", legacy))).as(legacy).isEqualTo(BoardStatus.WAITING);
    }

    @Test
    void 완료로_확인된_적_없이_사라진_건은_작업완료가_아니라_추적불가다() {
        holds("A", false);
        assertThat(backfill.migrate(req("A", "DEV"))).isEqualTo(BoardStatus.UNTRACKED);
        assertThat(backfill.migrate(req("A", "HOLD"))).isEqualTo(BoardStatus.UNTRACKED);
    }

    @Test
    void run_은_예전_버킷만_옮기고_이미_4단계인_행은_건드리지_않는다() {
        ItsmRequest legacy = req("L", "DEV");
        ItsmRequest current = req("C", BoardStatus.IN_PROGRESS);
        holds("L", true);
        when(reqRepo.findAll()).thenReturn(List.of(legacy, current));

        backfill.run(null);

        assertThat(legacy.getWorkStatus()).isEqualTo(BoardStatus.WAITING);
        assertThat(current.getWorkStatus()).isEqualTo(BoardStatus.IN_PROGRESS);
        verify(reqRepo).save(legacy);
        verify(reqRepo, never()).save(current);
    }

    @Test
    void 여러_번_돌려도_결과가_같다() {
        ItsmRequest r = req("L", "ANAL");
        holds("L", true);
        when(reqRepo.findAll()).thenReturn(List.of(r));
        backfill.run(null);
        backfill.run(null);
        assertThat(r.getWorkStatus()).isEqualTo(BoardStatus.WAITING);
        verify(reqRepo, times(1)).save(r);
    }

    @Test
    void 이관된_작업완료의_완료일은_이력에서_소급한다() {
        ItsmRequest r = req("D", "DONE");
        when(reqRepo.findAll()).thenReturn(List.of(r));
        when(histRepo.findByReqNoOrderByObservedAtDesc("D")).thenReturn(List.of(h("DONE", CLOSED, T_CLOSED)));
        backfill.run(null);
        assertThat(r.getDoneAt()).isEqualTo(T_CLOSED);
    }

    // --- 완료일 소급 ---

    private StatusBackfill withHistory(RequestStatusHistory... descHistory) {
        when(histRepo.findByReqNoOrderByObservedAtDesc(anyString())).thenReturn(List.of(descHistory));
        return backfill;
    }

    @Test
    void 완료_전이를_본_시각이_완료일이다() {
        StatusBackfill b = withHistory(h("DONE", CLOSED, T_CLOSED), h("DONE", "변경완료확인", T_DONE), h("DEP", "배포승인", T_DONE.minusHours(4)));
        // 내려간 시각(18:00)이 아니라 완료를 처음 본 시각(14:30)
        assertThat(b.deriveDoneAt(req("D", "DONE"))).isEqualTo(T_DONE);
    }

    @Test
    void 완료_전이를_못_봤으면_내려간_시각으로_대신한다() {
        StatusBackfill b = withHistory(h("DEP", CLOSED, T_CLOSED), h("DEP", "배포승인", T_DONE));
        assertThat(b.deriveDoneAt(req("D", "DONE"))).isEqualTo(T_CLOSED);
    }

    @Test
    void 이력이_아예_없으면_마지막_변경_시각으로_근사한다() {
        assertThat(withHistory().deriveDoneAt(req("D", "DONE"))).isEqualTo(T_DONE);
    }
}
