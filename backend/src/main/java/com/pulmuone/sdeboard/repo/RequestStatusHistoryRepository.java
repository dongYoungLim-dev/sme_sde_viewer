package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestStatusHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestStatusHistoryRepository extends JpaRepository<RequestStatusHistory, Long> {
    List<RequestStatusHistory> findByReqNoOrderByObservedAtDesc(String reqNo);
    List<RequestStatusHistory> findByOrderByObservedAtDesc(Pageable pageable);
}
