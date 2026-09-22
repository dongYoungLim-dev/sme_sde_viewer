package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequestScheduleRepository extends JpaRepository<RequestSchedule, Long> {
    Optional<RequestSchedule> findFirstByReqNoAndStatus(String reqNo, String status);
    List<RequestSchedule> findByReqNoOrderByIdDesc(String reqNo);
    List<RequestSchedule> findByReqNoIn(Collection<String> reqNos);
    /** 캘린더 — 기간과 겹치는 **유효한** 일정. */
    List<RequestSchedule> findByStatusAndStartDtLessThanAndEndDtGreaterThan(String status, LocalDateTime rangeEnd, LocalDateTime rangeStart);
}
