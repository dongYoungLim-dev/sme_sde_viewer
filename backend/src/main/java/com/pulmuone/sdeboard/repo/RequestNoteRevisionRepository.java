package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestNoteRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestNoteRevisionRepository extends JpaRepository<RequestNoteRevision, Long> {

    /** 이력 목록 — 최신 공유가 위. 팝업 왼쪽이 이 순서 그대로 그린다. */
    List<RequestNoteRevision> findByReqNoOrderBySeqDesc(String reqNo);

    /** 팝업 오른쪽(한 건 본문). `seq` 는 요청 안에서만 유일하므로 `reqNo` 와 함께 찾는다. */
    Optional<RequestNoteRevision> findByReqNoAndSeq(String reqNo, Integer seq);

    long countByReqNo(String reqNo);
}
