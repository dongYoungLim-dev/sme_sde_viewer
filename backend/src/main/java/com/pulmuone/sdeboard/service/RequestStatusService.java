package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppTime;
import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestStatusHistoryRepository;
import com.pulmuone.sdeboard.security.UserSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * 사람이 직접 바꾸는 요청 상태 — **추적불가 → 작업완료 확정** 하나뿐이다(사용자 결정 §8-3).
 *
 * <p>스케줄 없이 ITSM 목록에서 사라진 건은 이 보드를 안 쓰고 처리됐을 수 있다(가입자 전원이 쓴다는 보장이 없다 —
 * SME 뿐 아니라 리더·SDE 도). 자동으로 완료라고 <b>추정하지 않고</b> 사람이 확인해 확정한다.
 * 확정한 사람과 시각을 남기고, 그 시각이 완료일이 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RequestStatusService {

    private final ItsmRequestRepository reqRepo;
    private final RequestStatusHistoryRepository histRepo;
    private final AppUserRepository userRepo;
    private final DashboardService dashboard;

    public ItsmRequest completeUntracked(UserSession s, String reqNo) {
        ItsmRequest r = dashboard.requireInScopeRequest(s, reqNo);
        if (!BoardStatus.UNTRACKED.equals(r.getWorkStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "추적불가 상태의 요청만 작업완료로 확정할 수 있습니다.");

        AppUser me = userRepo.findById(s.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        LocalDateTime now = AppTime.now();
        r.setWorkStatus(BoardStatus.DONE);
        if (r.getDoneAt() == null) r.setDoneAt(now);
        r.setCompletedManuallyBy(me.getId());
        r.setCompletedManuallyAt(now);
        r.setLastChangedAt(now);
        reqRepo.save(r);

        RequestStatusHistory h = new RequestStatusHistory();
        h.setReqNo(r.getReqNo());
        h.setFromStatus(BoardStatus.UNTRACKED); h.setToStatus(BoardStatus.DONE);
        h.setFromStaNm(r.getItsmStaNm());       h.setToStaNm("완료 확정(수동)");
        h.setToAssignee(r.getAssigneeName());
        h.setActorPerId(me.getItsmPerId());
        h.setActorName(me.getName());
        h.setObservedAt(now);
        histRepo.save(h);
        return r;
    }
}
