package com.back.nbe1214finalsour.domain.university.repository;

import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.projections.UnivCacheRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UniversityRepository extends JpaRepository<University, Long> {

    Optional<University> findByName(String name);
    boolean existsByName(String name);
    // 학교 이름 부분 검색 + 이름순 정렬. 숨김 학교(운영팀용)는 검색에 나오지 않는다 (SCRUM-236)
    List<University> findByHiddenFalseAndNameContainingIgnoreCaseOrderByNameAsc(String keyword, Pageable pageable);

    @Query("select u.id, u.name, u.libraryLat, u.libraryLng, u.hidden from University u")
    List<UnivCacheRow> findAllForCache();

    // 순위 복사본(학과·개인)을 만들 학교 목록
    @Query("select u.id from University u")
    List<Long> findAllIds();
}
