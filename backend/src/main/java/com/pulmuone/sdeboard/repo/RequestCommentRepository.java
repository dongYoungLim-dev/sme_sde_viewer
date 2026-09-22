package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RequestCommentRepository extends JpaRepository<RequestComment, Long> {

    /** 한 요청의 한 채널 — 시간순(append-only 라 곧 대화 순서다). */
    List<RequestComment> findByReqNoAndChannelOrderByCreatedAtAsc(String reqNo, String channel);

    /** 목록 배지용 — 여러 건을 한 번에 읽는다(요청마다 질의하면 목록 한 장에 수십 번이 된다). */
    List<RequestComment> findByReqNoIn(Collection<String> reqNos);
}
