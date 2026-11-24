package com.back.nbe1214finalsour.domain.university.service.rival;

import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 경쟁 학교 그룹({@code rivals.groups})을 학교 ID끼리의 양방향 관계로 바꾸는 규칙을 검증한다.
 * 학교 조회는 목이고, 실제 {@code application.yaml} 바인딩은 {@code RivalCheckerIntegrationTest}에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class RivalCheckerTest {

    private static final long YONSEI = 1L;
    private static final long KOREA = 2L;
    private static final long SNU = 3L;
    private static final long KAIST = 4L;

    @Mock
    private UniversityRepository universityRepository;

    private RivalChecker load(List<List<String>> groups) {
        RivalChecker rivalChecker = new RivalChecker(new RivalProperties(groups), universityRepository);
        rivalChecker.load();
        return rivalChecker;
    }

    private void givenUniversity(String name, long id) {
        University university = mock(University.class);
        given(university.getId()).willReturn(id);
        given(universityRepository.findByName(name)).willReturn(Optional.of(university));
    }

    // --- 판정 ---

    @Test
    @DisplayName("두 학교 그룹이면 어느 쪽에서 물어도 경쟁 학교다")
    void pairIsRivalInBothDirections() {
        givenUniversity("연세대학교", YONSEI);
        givenUniversity("고려대학교", KOREA);

        RivalChecker rivalChecker = load(List.of(List.of("연세대학교", "고려대학교")));

        assertThat(rivalChecker.isRival(YONSEI, KOREA)).isTrue();
        assertThat(rivalChecker.isRival(KOREA, YONSEI)).isTrue();
    }

    @Test
    @DisplayName("세 학교 그룹이면 그룹 안의 모든 조합이 서로 경쟁 학교다")
    void groupOfThreeIsRivalForEveryCombination() {
        givenUniversity("연세대학교", YONSEI);
        givenUniversity("고려대학교", KOREA);
        givenUniversity("서울대학교", SNU);

        RivalChecker rivalChecker = load(List.of(List.of("서울대학교", "연세대학교", "고려대학교")));

        assertThat(rivalChecker.isRival(SNU, YONSEI)).isTrue();
        assertThat(rivalChecker.isRival(SNU, KOREA)).isTrue();
        assertThat(rivalChecker.isRival(YONSEI, SNU)).isTrue();
        assertThat(rivalChecker.isRival(YONSEI, KOREA)).isTrue();
        assertThat(rivalChecker.isRival(KOREA, SNU)).isTrue();
        assertThat(rivalChecker.isRival(KOREA, YONSEI)).isTrue();
    }

    @Test
    @DisplayName("자기 자신은 경쟁 학교가 아니다")
    void universityIsNotItsOwnRival() {
        givenUniversity("연세대학교", YONSEI);
        givenUniversity("고려대학교", KOREA);

        RivalChecker rivalChecker = load(List.of(List.of("연세대학교", "고려대학교")));

        assertThat(rivalChecker.isRival(YONSEI, YONSEI)).isFalse();
    }

    @Test
    @DisplayName("다른 그룹의 학교나 그룹에 없는 학교는 경쟁 학교가 아니다")
    void universitiesOutsideGroupAreNotRivals() {
        givenUniversity("연세대학교", YONSEI);
        givenUniversity("고려대학교", KOREA);
        givenUniversity("서울대학교", SNU);
        givenUniversity("KAIST", KAIST);

        RivalChecker rivalChecker = load(List.of(
                List.of("연세대학교", "고려대학교"),
                List.of("서울대학교", "KAIST")));

        assertThat(rivalChecker.isRival(YONSEI, SNU)).isFalse();
        assertThat(rivalChecker.isRival(KAIST, KOREA)).isFalse();
        assertThat(rivalChecker.isRival(YONSEI, 999L)).isFalse();
        assertThat(rivalChecker.isRival(999L, YONSEI)).isFalse();
    }

    @Test
    @DisplayName("한 학교가 여러 그룹에 있으면 경쟁 학교를 합치고, 다른 그룹끼리는 이어지지 않는다")
    void universityInSeveralGroupsMergesRivals() {
        givenUniversity("연세대학교", YONSEI);
        givenUniversity("고려대학교", KOREA);
        givenUniversity("서울대학교", SNU);

        RivalChecker rivalChecker = load(List.of(
                List.of("연세대학교", "고려대학교"),
                List.of("서울대학교", "연세대학교")));

        assertThat(rivalChecker.isRival(YONSEI, KOREA)).isTrue();
        assertThat(rivalChecker.isRival(YONSEI, SNU)).isTrue();
        // 고려대와 서울대는 같은 그룹에 있은 적이 없다
        assertThat(rivalChecker.isRival(KOREA, SNU)).isFalse();
    }

    @Test
    @DisplayName("rivals.groups 설정이 없으면 경쟁 학교가 없고 학교도 조회하지 않는다")
    void noGroupsMeansNoRivals() {
        RivalChecker rivalChecker = load(null);

        assertThat(rivalChecker.isRival(YONSEI, KOREA)).isFalse();
        verify(universityRepository, never()).findByName(anyString());
    }

    // --- 잘못된 설정은 서버가 뜰 때 막는다 ---

    @Test
    @DisplayName("학교가 1개뿐인 그룹이 있으면 IllegalStateException")
    void groupWithOneUniversityFailsToLoad() {
        assertThatThrownBy(() -> load(List.of(List.of("연세대학교"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연세대학교");
    }

    @Test
    @DisplayName("DB에 없는 학교 이름이 있으면 그 이름을 담아 IllegalStateException")
    void unknownUniversityNameFailsToLoad() {
        givenUniversity("연세대학교", YONSEI);
        given(universityRepository.findByName("고려대")).willReturn(Optional.empty());

        assertThatThrownBy(() -> load(List.of(List.of("연세대학교", "고려대"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("고려대");
    }
}
