package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.CommentRead;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommentReadRepository extends JpaRepository<CommentRead, Long> {

    Optional<CommentRead> findByReqNoAndUserIdAndChannel(String reqNo, Long userId, String channel);

    List<CommentRead> findByUserIdAndReqNoIn(Long userId, Collection<String> reqNos);
}
