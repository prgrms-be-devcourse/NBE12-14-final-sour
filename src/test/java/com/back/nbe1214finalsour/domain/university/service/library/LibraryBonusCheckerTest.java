package com.back.nbe1214finalsour.domain.university.service.library;

import com.back.nbe1214finalsour.domain.university.dto.LibraryLocation;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 클릭한 위치가 내 학교 중앙도서관에서 반경 100m 안(경계 포함)인지 판정하는 규칙을 검증한다.
 *
 * <p>기대 거리는 지구 반지름 6,371km 기준 대원 거리로 미리 계산한 값이다.
 * 위도 0.001°는 어디서나 약 111m이지만, 경도 0.001°는 위도 37.56°에서 약 88m로 짧아진다.
 */
@ExtendWith(MockitoExtension.class)
class LibraryBonusCheckerTest {

    private static final long UNIV_ID = 1L;

    /** 연세대학교 중앙도서관 (V9 시드 좌표) */
    private static final double LIB_LAT = 37.563764;
    private static final double LIB_LNG = 126.937012;

    @Mock
    private UniversityCache universityCache;

    private LibraryBonusChecker libraryBonusChecker;

    @BeforeEach
    void setUp() {
        libraryBonusChecker = new LibraryBonusChecker(universityCache);
    }

    private void givenLibrary() {
        given(universityCache.getLibrary(UNIV_ID))
                .willReturn(Optional.of(new LibraryLocation(LIB_LAT, LIB_LNG)));
    }

    // --- 반경 안 ---

    @Test
    @DisplayName("도서관 좌표와 같은 위치면 보너스 대상이다")
    void samePointIsInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT, LIB_LNG)).isTrue();
    }

    @Test
    @DisplayName("북쪽으로 약 89m(위도 +0.0008°)면 보너스 대상이다")
    void about89mNorthIsInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT + 0.0008, LIB_LNG)).isTrue();
    }

    @Test
    @DisplayName("남쪽으로 약 89m(위도 -0.0008°)면 보너스 대상이다")
    void about89mSouthIsInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT - 0.0008, LIB_LNG)).isTrue();
    }

    @Test
    @DisplayName("동쪽으로 경도 +0.0010°는 이 위도에서 약 88m라 보너스 대상이다")
    void about88mEastIsInLibrary() {
        givenLibrary();

        // 경도를 위도처럼 0.001° = 111m로 계산하면 여기서 틀린다
        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT, LIB_LNG + 0.0010)).isTrue();
    }

    // --- 반경 밖 ---

    @Test
    @DisplayName("북쪽으로 약 111m(위도 +0.0010°)면 보너스 대상이 아니다")
    void about111mNorthIsNotInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT + 0.0010, LIB_LNG)).isFalse();
    }

    @Test
    @DisplayName("동쪽으로 약 115m(경도 +0.0013°)면 보너스 대상이 아니다")
    void about115mEastIsNotInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT, LIB_LNG + 0.0013)).isFalse();
    }

    @Test
    @DisplayName("다른 학교 도서관(고려대, 약 9km)에서는 보너스 대상이 아니다")
    void otherUniversityLibraryIsNotInLibrary() {
        givenLibrary();

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, 37.590800, 127.034099)).isFalse();
    }

    // --- 판정할 수 없으면 보너스 없음 ---

    @Test
    @DisplayName("학교에 도서관 좌표가 없으면 보너스 대상이 아니다")
    void universityWithoutLibraryIsNotInLibrary() {
        given(universityCache.getLibrary(UNIV_ID)).willReturn(Optional.empty());

        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT, LIB_LNG)).isFalse();
    }

    @Test
    @DisplayName("위치를 보내지 않으면 보너스 대상이 아니고 도서관 좌표도 찾지 않는다")
    void noLocationIsNotInLibrary() {
        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, null, null)).isFalse();
        verify(universityCache, never()).getLibrary(anyLong());
    }

    @Test
    @DisplayName("위도나 경도 중 하나만 보내면 보너스 대상이 아니다")
    void partialLocationIsNotInLibrary() {
        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, LIB_LAT, null)).isFalse();
        assertThat(libraryBonusChecker.isInLibrary(UNIV_ID, null, LIB_LNG)).isFalse();
        verify(universityCache, never()).getLibrary(anyLong());
    }
}
