package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.NoteRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NoteReadRepository extends JpaRepository<NoteRead, Long> {

    Optional<NoteRead> findByReqNoAndUserId(String reqNo, Long userId);

    /** 목록 배지용 — 요청마다 한 번씩 묻지 않도록 이 사용자의 읽음 기록을 한 번에 읽는다. */
    List<NoteRead> findByUserIdAndReqNoIn(Long userId, Collection<String> reqNos);
}
