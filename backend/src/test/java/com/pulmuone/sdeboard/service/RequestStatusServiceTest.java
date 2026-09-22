package com.pulmuone.sdeboard.service;

import com.pulmuone.sdeboard.domain.AppUser;
import com.pulmuone.sdeboard.domain.BoardStatus;
import com.pulmuone.sdeboard.domain.ItsmRequest;
import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import com.pulmuone.sdeboard.repo.AppUserRepository;
import com.pulmuone.sdeboard.repo.ItsmRequestRepository;
import com.pulmuone.sdeboard.repo.RequestStatusHistoryRepository;
import com.pulmuone.sdeboard.security.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 추적불가 → 작업완료 **사람의 확정**(`UR-260922-1` §8-3). 추정이 아니라 확인이라는 점이 규칙의 전부다. */
class RequestStatusServiceTest {

    private static final String REQ = "CSD1";
    private ItsmRequestRepository reqRepo;
    private RequestStatusHistoryRepository histRepo;
    private DashboardService dashboard;
    private RequestStatusService svc;
    private ItsmRequest req;
    private UserSession session;

    @BeforeEach
    void setUp() {
        reqRepo = mock(ItsmRequestRepository.class);
        histRepo = mock(RequestStatusHistoryRepository.class);
        AppUserRepository userRepo = mock(AppUserRepository.class);
        dashboard = mock(DashboardService.class);
        svc = new RequestStatusService(reqRepo, histRepo, userRepo, dashboard);

        req = new ItsmRequest();
        req.setReqNo(REQ);
        req.setWorkStatus(BoardStatus.UNTRACKED);
        session = new UserSession();
        session.setUserId(7L); session.setRole("SDE");
        AppUser me = new AppUser();
        me.setId(7L); me.setName("임동영");
        when(userRepo.findById(7L)).thenReturn(Optional.of(me));
        when(dashboard.requireInScopeRequest(any(), eq(REQ))).thenReturn(req);
    }

    @Test
    void 추적불가를_확정하면_작업완료가_되고_누가_언제_확정했는지_남는다() {
        svc.completeUntracked(session, REQ);

        assertThat(req.getWorkStatus()).isEqualTo(BoardStatus.DONE);
        assertThat(req.getDoneAt()).isNotNull();
        assertThat(req.getCompletedManuallyBy()).isEqualTo(7L);
        assertThat(req.getCompletedManuallyAt()).isNotNull();
        ArgumentCaptor<RequestStatusHistory> h = ArgumentCaptor.forClass(RequestStatusHistory.class);
        verify(histRepo).save(h.capture());
        assertThat(h.getValue().getFromStatus()).isEqualTo(BoardStatus.UNTRACKED);
        assertThat(h.getValue().getToStatus()).isEqualTo(BoardStatus.DONE);
        assertThat(h.getValue().getActorName()).isEqualTo("임동영");
    }

    @Test
    void 추적불가가_아니면_확정할_수_없다() {
        for (String s : new String[]{BoardStatus.WAITING, BoardStatus.IN_PROGRESS, BoardStatus.DONE}) {
            req.setWorkStatus(s);
            assertThatThrownBy(() -> svc.completeUntracked(session, REQ))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(req.getCompletedManuallyBy()).isNull();
        verify(histRepo, never()).save(any());
    }
}
