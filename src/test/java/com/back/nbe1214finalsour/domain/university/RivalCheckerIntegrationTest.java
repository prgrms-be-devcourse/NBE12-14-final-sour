package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.university.service.rival.RivalChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yaml}의 {@code rivals.groups}가 실제로 읽혀 시드 학교끼리 경쟁 학교가 되는지 확인한다.
 *
 * <p>키 이름이 레코드 필드와 어긋나면 서버는 오류 없이 뜨고 경쟁 학교만 0개가 된다. 그 경우를 여기서 잡는다.
 */
@IntegrationTest
class RivalCheckerIntegrationTest {

    @Autowired
    private RivalChecker rivalChecker;
    @Autowired
    private UniversityRepository universityRepository;

    @Test
    @DisplayName("설정의 두 학교 그룹(연세대–고려대)은 양방향으로 경쟁 학교다")
    void configuredPairIsRival() {
        assertThat(rivalChecker.isRival(id("연세대학교"), id("고려대학교"))).isTrue();
        assertThat(rivalChecker.isRival(id("고려대학교"), id("연세대학교"))).isTrue();
    }

    @Test
    @DisplayName("설정의 세 학교 그룹(서강대·성균관대·한양대)은 서로 경쟁 학교다")
    void configuredGroupOfThreeIsRival() {
        assertThat(rivalChecker.isRival(id("서강대학교"), id("한양대학교"))).isTrue();
        assertThat(rivalChecker.isRival(id("한양대학교"), id("성균관대학교"))).isTrue();
        assertThat(rivalChecker.isRival(id("성균관대학교"), id("서강대학교"))).isTrue();
    }

    @Test
    @DisplayName("다른 그룹의 학교는 경쟁 학교가 아니다")
    void universitiesInDifferentGroupsAreNotRivals() {
        assertThat(rivalChecker.isRival(id("연세대학교"), id("KAIST"))).isFalse();
    }

    private Long id(String name) {
        return universityRepository.findByName(name).orElseThrow().getId();
    }
}
