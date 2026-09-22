package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.SyncLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncLogRepository extends JpaRepository<SyncLog, Long> {
}
