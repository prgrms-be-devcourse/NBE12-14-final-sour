package com.back.nbe1214finalsour.domain.university;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.university.dto.LibraryLocation;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 학교 이름(공격·지원 대상 확인)과 중앙도서관 좌표(도서관 이스터에그)를 DB 대신 메모리에서 돌려주는 캐시를 확인한다.
 *
 * <p>캐시는 스프링 컨텍스트에서 공유되는 싱글턴이다. 그래서 {@code @Transactional} 롤백을 쓰지 않고,
 * 테스트가 만든 학교는 직접 지운 뒤 캐시를 다시 불러와 다른 테스트에 남지 않게 한다.
 */
@IntegrationTest
class UniversityCacheIntegrationTest {

    /** DB에 없는 학교 ID */
    private static final Long UNKNOWN_UNIV_ID = 999_999_999L;

    @Autowired
    private UniversityCache universityCache;
    @Autowired
    private UniversityRepository universityRepository;

    private final List<Long> createdUnivIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!createdUnivIds.isEmpty()) {
            universityRepository.deleteAllById(createdUnivIds);
            universityCache.load();
        }
    }

    @Test
    @DisplayName("서버가 뜰 때 시드 학교를 불러 두어 ID로 이름을 돌려준다")
    void returnsNameOfSeededUniversity() {
        // given: V2__seed_universities.sql로 들어간 학교
        Long seoulId = universityRepository.findByName("서울대학교").orElseThrow().getId();

        // when
        String name = universityCache.getName(seoulId);

        // then
        assertThat(name).isEqualTo("서울대학교");
    }

    @Test
    @DisplayName("DB의 모든 학교를 캐시에서 찾을 수 있다")
    void containsEveryUniversityInDb() {
        // given
        List<University> universities = universityRepository.findAll();

        // when & then
        assertThat(universities).isNotEmpty();
        assertThat(universities).allSatisfy(university ->
                assertThat(universityCache.getName(university.getId())).isEqualTo(university.getName()));
    }

    @Test
    @DisplayName("없는 학교 ID면 UNIV_NOT_FOUND를 던진다")
    void throwsWhenUniversityDoesNotExist() {
        assertThatThrownBy(() -> universityCache.getName(UNKNOWN_UNIV_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNIV_NOT_FOUND);
    }

    @Test
    @DisplayName("서버가 뜬 뒤 추가된 학교는 다시 불러오기 전까지 찾지 못한다")
    void findsUniversityAddedAfterStartupOnlyAfterReload() {
        // given
        University added = universityRepository.save(new University("캐시테스트대학교"));
        createdUnivIds.add(added.getId());

        // when & then: 아직 캐시에 없음
        assertThatThrownBy(() -> universityCache.getName(added.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNIV_NOT_FOUND);

        // when: 다시 불러오면
        universityCache.load();

        // then
        assertThat(universityCache.getName(added.getId())).isEqualTo("캐시테스트대학교");
    }

    // --- 도서관 좌표 ---

    @Test
    @DisplayName("시드 학교의 도서관 좌표를 돌려준다")
    void returnsLibraryLocationOfSeededUniversity() {
        // given: V9__seed_university_library_location.sql로 들어간 좌표
        Long yonseiId = universityRepository.findByName("연세대학교").orElseThrow().getId();

        // when
        Optional<LibraryLocation> library = universityCache.getLibrary(yonseiId);

        // then
        assertThat(library).contains(new LibraryLocation(37.563764, 126.937012));
    }

    @Test
    @DisplayName("좌표가 있는 DB의 모든 학교는 캐시에서도 같은 좌표를 돌려준다")
    void containsLibraryLocationOfEveryUniversityInDb() {
        // given
        List<University> located = universityRepository.findAll().stream()
                .filter(university -> university.getLibraryLat() != null)
                .toList();

        // when & then
        assertThat(located).isNotEmpty();
        assertThat(located).allSatisfy(university ->
                assertThat(universityCache.getLibrary(university.getId())).contains(new LibraryLocation(
                        university.getLibraryLat().doubleValue(), university.getLibraryLng().doubleValue())));
    }

    @Test
    @DisplayName("도서관 좌표가 없는 학교면 빈 값을 돌려준다")
    void returnsEmptyWhenLibraryLocationIsMissing() {
        // given: 좌표 없이 만든 학교
        University added = universityRepository.save(new University("좌표없는테스트대학교"));
        createdUnivIds.add(added.getId());
        universityCache.load();

        // when
        Optional<LibraryLocation> library = universityCache.getLibrary(added.getId());

        // then
        assertThat(library).isEmpty();
    }

    @Test
    @DisplayName("없는 학교 ID로 도서관 좌표를 찾으면 UNIV_NOT_FOUND를 던진다")
    void throwsWhenLibraryOfUnknownUniversityIsRequested() {
        assertThatThrownBy(() -> universityCache.getLibrary(UNKNOWN_UNIV_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNIV_NOT_FOUND);
    }
}
