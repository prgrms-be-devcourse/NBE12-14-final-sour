package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllianceMatchRedisSettlementServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private UniversityCache universityCache;

    @InjectMocks
    private AllianceMatchRedisSettlementService service;

    @Test
    @DisplayName("Redis 정산이 처음 적용되면 true를 반환하고 Lua에 학교·기여도·숨김 여부를 전달한다")
    void settleOnce_returnsTrueAndPassesExpectedArguments() {
        // given
        stubRedisResult(1L);

        // when
        boolean applied = service.settleOnce(99L, 11L, 12L, 21L, 22L, 30L, 40L);

        // then
        assertThat(applied).isTrue();
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(expectedKeys()),
                eq("11"), eq("12"), eq("21"), eq("22"),
                eq("30"), eq("40"),
                eq("0"), eq("0"), eq("0"), eq("0")
        );
        verify(universityCache).isHidden(11L);
        verify(universityCache).isHidden(12L);
        verify(universityCache).isHidden(21L);
        verify(universityCache).isHidden(22L);
    }

    @Test
    @DisplayName("이미 정산된 대결에 대해 Redis가 0을 반환하면 false를 반환한다")
    void settleOnce_returnsFalseWhenAlreadySettled() {
        stubRedisResult(0L);

        assertThat(service.settleOnce(99L, 11L, 12L, 21L, 22L, 30L, 40L))
                .isFalse();
    }

    @Test
    @DisplayName("Redis가 정산 결과를 반환하지 않으면 예외를 발생시킨다")
    void settleOnce_rejectsMissingRedisResult() {
        // RedisTemplate의 미설정 반환값은 null
        assertThatThrownBy(() -> service.settleOnce(99L, 11L, 12L, 21L, 22L, 30L, 40L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 정산 결과를 확인할 수 없습니다.");
    }

    @Test
    @DisplayName("숨김 학교는 Lua 정산 스크립트에 숨김 플래그 1로 전달한다")
    void settleOnce_passesHiddenUniversityFlags() {
        // given: 패배 학교 하나와 승리 학교 하나가 숨김 상태
        doReturn(true).when(universityCache).isHidden(11L);
        doReturn(false).when(universityCache).isHidden(12L);
        doReturn(false).when(universityCache).isHidden(21L);
        doReturn(true).when(universityCache).isHidden(22L);
        stubRedisResult(1L);

        // when
        assertThat(service.settleOnce(99L, 11L, 12L, 21L, 22L, 30L, 40L))
                .isTrue();

        // then: 학교 순서는 loserA, loserB, winnerA, winnerB
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(expectedKeys()),
                eq("11"), eq("12"), eq("21"), eq("22"),
                eq("30"), eq("40"),
                eq("1"), eq("0"), eq("0"), eq("1")
        );
    }

    @ParameterizedTest(name = "잘못된 대결·학교 ID: match={0}, losers={1}/{2}, winners={3}/{4}")
    @CsvSource({
            "0, 11, 12, 21, 22",
            "-1, 11, 12, 21, 22",
            "99, 0, 12, 21, 22",
            "99, 11, 0, 21, 22",
            "99, 11, 12, 0, 22",
            "99, 11, 12, 21, 0"
    })
    @DisplayName("대결 및 학교 ID는 모두 양수여야 한다")
    void settleOnce_rejectsNonPositiveIds(
            long matchId, long loserA, long loserB, long winnerA, long winnerB
    ) {
        assertThatThrownBy(() -> service.settleOnce(
                matchId, loserA, loserB, winnerA, winnerB, 30L, 40L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 대상 ID가 올바르지 않습니다.");

        verifyNoInteractions(redisTemplate, universityCache);
    }

    @ParameterizedTest(name = "중복 학교 IDs: losers={0}/{1}, winners={2}/{3}")
    @CsvSource({
            "11, 11, 21, 22",
            "11, 12, 11, 22",
            "11, 12, 21, 21"
    })
    @DisplayName("패배·승리 학교 네 곳은 모두 서로 달라야 한다")
    void settleOnce_rejectsDuplicateUniversities(
            long loserA, long loserB, long winnerA, long winnerB
    ) {
        assertThatThrownBy(() -> service.settleOnce(
                99L, loserA, loserB, winnerA, winnerB, 30L, 40L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 대상 학교는 서로 달라야 합니다.");

        verifyNoInteractions(redisTemplate, universityCache);
    }

    @ParameterizedTest(name = "음수 기여도: A={0}, B={1}")
    @CsvSource({
            "-1, 40",
            "30, -1"
    })
    @DisplayName("패배 학교의 기여도는 음수일 수 없다")
    void settleOnce_rejectsNegativeContributions(long contributionA, long contributionB) {
        assertThatThrownBy(() -> service.settleOnce(
                99L, 11L, 12L, 21L, 22L, contributionA, contributionB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대결 기여도는 음수일 수 없습니다.");

        verifyNoInteractions(redisTemplate, universityCache);
    }

    @Test
    @DisplayName("기여도가 0이면 유효한 정산 요청으로 Lua를 실행한다")
    void settleOnce_acceptsZeroContributions() {
        stubRedisResult(1L);

        assertThat(service.settleOnce(99L, 11L, 12L, 21L, 22L, 0L, 0L))
                .isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(expectedKeys()),
                eq("11"), eq("12"), eq("21"), eq("22"),
                eq("0"), eq("0"),
                eq("0"), eq("0"), eq("0"), eq("0")
        );
    }

    private void stubRedisResult(Long result) {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()
        )).thenReturn(result);
    }

    private static List<String> expectedKeys() {
        return List.of(
                RedisKey.RANK_UNIV.of(),
                RedisKey.DIRTY_UNIV.of(),
                "match:99:settled",
                RedisKey.RANK_UNIV_HIDDEN.of()
        );
    }
}
