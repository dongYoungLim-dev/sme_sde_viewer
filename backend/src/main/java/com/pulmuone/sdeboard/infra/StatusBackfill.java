package com.pulmuone.sdeboard.infra;

import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmFlow;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestOwnerRepository;
import com.pulmuone.sdeboard.repo.RequestStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 기동 시 1회 — **예전 상태값을 4단계({@link BoardStatus})로 옮기고**, 할당 단계·완료 시각을 채운다.
 *
 * <h3>상태 이관 (2026-09-22, `UR-260922-1`, 사용자 결정 §8-4)</h3>
 * 폐기된 버킷(NEW/ANAL/DEV/TEST/DEP/HOLD 또는 null)을 가진 행만 대상이라 <b>몇 번을 돌려도 안전하다</b>
 * (이관된 행은 4단계 값이라 다시 건드리지 않는다).
 * <ul>
 *   <li>예전 {@code DONE}(완료로 관측·추정된 건) → {@code DONE} 그대로</li>
 *   <li>그 외 + 지금 <b>아무도 To-Do 에 안 들고 있음</b> → {@code UNTRACKED}(추적불가)<br>
 *       ⚠️ 사용자 답변은 "처리 완료 건 → 작업완료, 신규·진행중 → 대기" 였다. 완료로 확인된 적 없이 목록에서
 *       사라진 건은 이 둘 어느 쪽도 아니다 — 작업완료로 옮기면 완료 목록·주간보고가 조용히 부풀고,
 *       대기로 옮기면 이미 끝난 건이 진행 중인 것처럼 보인다. 그래서 새 모델의 <b>추적불가</b> 정의(스케줄 없이
 *       사라진 건 — 사람이 확정)에 그대로 넣는다.</li>
 *   <li>그 외 → {@code WAITING}(스케줄이 아직 없으므로). 과거 진행률·단계 정보는 버린다.</li>
 * </ul>
 *
 * <p>할당 단계(`assign_stage`)는 ITSM 상태에서 파생되므로 진행 중인 건에 한해 매번 다시 계산한다.
 * 완료된 건의 `done_at` 이 비어 있으면 관측 이력에서 소급해 채운다.
 */
@Component
@Order(1)                       // SchemaMigration(Order 0) 이 새 컬럼을 만든 뒤에 돈다
@RequiredArgsConstructor
@Slf4j
public class StatusBackfill implements ApplicationRunner {

    private final ItsmRequestRepository reqRepo;
    private final RequestStatusHistoryRepository histRepo;
    private final RequestOwnerRepository ownerRepo;
    private final ItsmFlow flow;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = 0, staged = 0, dated = 0;
        for (ItsmRequest r : reqRepo.findAll()) {
            String oldStatus = r.getWorkStatus();
            String oldStage = r.getAssignStage();

            if (!BoardStatus.isCurrent(oldStatus)) {
                r.setWorkStatus(migrate(r));
                migrated++;
            }
            if (!BoardStatus.DONE.equals(r.getWorkStatus())) {
                r.recomputeStage(flow.seqOf(r.getItsmStaCd(), r.getItsmStaNm()), flow.assignFromSeq());
            }
            boolean stageDirty = !Objects.equals(oldStage, r.getAssignStage());
            if (stageDirty) staged++;

            LocalDateTime done = BoardStatus.DONE.equals(r.getWorkStatus()) && r.getDoneAt() == null ? deriveDoneAt(r) : null;
            if (done != null) { r.setDoneAt(done); dated++; }

            if (!Objects.equals(oldStatus, r.getWorkStatus()) || stageDirty || done != null) reqRepo.save(r);
        }
        if (migrated > 0) log.info("상태 이관 완료 — {}건 (예전 버킷 → 대기/추적불가/작업완료)", migrated);
        if (staged > 0) log.info("할당 단계 재계산 — {}건", staged);
        if (dated > 0) log.info("완료 시각 백필 완료 — {}건 (관측 이력에서 소급)", dated);
    }

    /** 예전 버킷 → 4단계. package-private — 테스트가 직접 부른다. */
    String migrate(ItsmRequest r) {
        if ("DONE".equals(r.getWorkStatus())) return BoardStatus.DONE;
        boolean anyoneHolds = !ownerRepo.findByReqNoAndActiveTrue(r.getReqNo()).isEmpty();
        return anyoneHolds ? BoardStatus.WAITING : BoardStatus.UNTRACKED;
    }

    /**
     * 이미 완료된 건의 `done_at` 을 **관측 이력에서 소급**한다.
     *
     * <p>ITSM 목록 응답에는 완료 시각 필드가 없다. 순서대로 찾는다 — ① DONE 으로 바뀐 걸 처음 본 시각
     * ② To-Do 에서 내려간 시각 ③ 그마저 없으면(미러링 시작 전에 이미 완료) `last_changed_at`.
     * ③ 은 완료 시각이라기보다 '이 값 말고 아는 게 없다' 는 뜻이라 화면이 "우리 관측 시각"이라고 밝힌다.
     */
    LocalDateTime deriveDoneAt(ItsmRequest r) {
        List<RequestStatusHistory> desc = histRepo.findByReqNoOrderByObservedAtDesc(r.getReqNo());
        LocalDateTime firstDone = null, closed = null;
        for (int i = desc.size() - 1; i >= 0; i--) {          // 과거 → 현재
            RequestStatusHistory h = desc.get(i);
            if (RequestStatusHistory.CLOSED_MARK.equals(h.getToStaNm())) {
                if (closed == null) closed = h.getObservedAt();
                continue;                                      // 내려감 표시는 완료 '전이'가 아니다
            }
            if (firstDone == null && BoardStatus.DONE.equals(h.getToStatus())) firstDone = h.getObservedAt();
        }
        if (firstDone != null) return firstDone;
        return closed != null ? closed : r.getLastChangedAt();
    }
}
