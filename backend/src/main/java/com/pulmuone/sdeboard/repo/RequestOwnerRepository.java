package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.RequestOwner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RequestOwnerRepository extends JpaRepository<RequestOwner, Long> {
    Optional<RequestOwner> findByUserIdAndReqNo(Long userId, String reqNo);
    List<RequestOwner> findByUserIdAndActiveTrue(Long userId);
    List<RequestOwner> findByUserIdInAndActiveTrue(List<Long> userIds);
    /** 완료돼 To-Do 에서 내려간 건도 화면에 남겨야 하므로 active 무관하게 읽는다. */
    List<RequestOwner> findByUserIdIn(List<Long> userIds);
    List<RequestOwner> findByReqNoAndActiveTrue(String reqNo);
    /** 요청 기준 소유자 전체. inTodo 를 **범위 멤버가 아니라 전체 소유자**로 판정하기 위해 필요하다. */
    List<RequestOwner> findByReqNoIn(List<String> reqNos);
}
