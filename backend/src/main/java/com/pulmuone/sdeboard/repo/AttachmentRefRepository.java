package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.AttachmentRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRefRepository extends JpaRepository<AttachmentRef, Long> {
    List<AttachmentRef> findByReqNo(String reqNo);
}
