package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.ItsmRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ItsmRequestRepository extends JpaRepository<ItsmRequest, Long> {
    Optional<ItsmRequest> findByReqNo(String reqNo);
    List<ItsmRequest> findAllByOrderByDueDateAsc();
    List<ItsmRequest> findByReqNoIn(Collection<String> reqNos);
}
