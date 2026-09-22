package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestNoteRepository extends JpaRepository<RequestNote, Long> {
    Optional<RequestNote> findByReqNo(String reqNo);
    /** 목록의 '노트 있음' 배지용 — 요청마다 한 번씩 묻지 않도록 한 번에 읽는다. */
    List<RequestNote> findByReqNoIn(List<String> reqNos);
}
