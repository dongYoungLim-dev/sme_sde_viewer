package com.pulmuone.sdeboard.repo;

import com.pulmuone.sdeboard.domain.SdeAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SdeAssignmentRepository extends JpaRepository<SdeAssignment, Long> {
    List<SdeAssignment> findByUserId(Long userId);
    List<SdeAssignment> findByUserIdIn(List<Long> userIds);
    List<SdeAssignment> findByCorpNm(String corpNm);
    /**
     * 여러 법인의 배정을 한 번에. SDE 화면이 <b>내가 배정된 법인의 다른 차수 담당자</b>를
     * 함께 보여주려면 내 행만으로는 부족하다 (`UR-260909-5`).
     */
    List<SdeAssignment> findByCorpNmIn(Collection<String> corpNms);
    /**
     * ⚠️ {@code findByCorpNmAndTier} 는 없앴다(2026-09-10 `UR-260910-1`).
     * 차수가 시스템 단위로 내려간 뒤로 <b>한 법인에 같은 차수가 여러 줄</b>(법인담당SDE + 시스템별) 존재한다.
     * 그 이름으로 찾으면 어느 줄이 나올지 모르고, {@code system_nm IS NULL} 을 파생 쿼리로 쓰면
     * 호출부마다 null 처리가 흩어진다. 대신 {@code findByCorpNm} 으로 받아 한 곳에서 고른다.
     */
}
