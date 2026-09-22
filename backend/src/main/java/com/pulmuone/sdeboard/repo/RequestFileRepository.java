package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RequestFileRepository extends JpaRepository<RequestFile, Long> {
    List<RequestFile> findByReqNoOrderByIdAsc(String reqNo);
    long countByReqNo(String reqNo);
    List<RequestFile> findByReqNoIn(Collection<String> reqNos);
}
