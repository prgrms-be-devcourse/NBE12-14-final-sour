package com.back.nbe1214finalsour.domain.university.repository;

import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.repository.projections.DeptUniv;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByUniversity_IdOrderByNameAsc(Long universityId);
    boolean existsByUniversity_IdAndName(Long universityId, String name);
    Optional<Department> findByUniversity_IdAndName(Long universityId, String name);
    // 특정 학교에서 누적 점수가 높은 학과부터 조회
    List<Department> findByUniversity_IdOrderByTotalScoreDescIdAsc(
            Long universityId,
            Pageable pageable
    );
    // 해당 학교에 소속되어 있으면서 지정한 ID에 해당하는 학과들만 조회
    List<Department> findByUniversity_IdAndIdIn(
            Long universityId,
            Collection<Long> departmentIds
    );

    @Query("select id, university.id from Department where id in :ids")
    List<DeptUniv> findUniversityIdsByIdIn(Collection<Long> ids);
}
