package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceStandingResolver.AllianceStanding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 학교의 현재 연합·대결 조회. 연합은 {@code univ:{u}:alliance}, 진행 중 대결은 {@code univ:{u}:match}("{matchId}:{A|B}")에 캐시한다.
 * 캐시가 없을 때만 DB를 보고, 없다는 결과도 "none"으로 10초 캐시한다. 연합이 없으면 대결은 보지 않는다.
 *
 * <p>"없음"은 {@link AllianceStanding#NONE}(0, 0, "")이고, {@code apply-click.lua}·{@code apply-assist.lua}가 이 값을 보고 건너뛴다.
 * 실제 Redis·DB와 함께 도는 경로는 {@code ClickIntegrationTest}, {@code AssistIntegrationTest}에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class AllianceStandingResolverTest {

    private static final Long UNIVERSITY_ID = 12L;
    private static final Long ALLIANCE_ID = 7L;
    private static final Long OTHER_ALLIANCE_ID = 8L;
    private static final Long MATCH_ID = 42L;
    private static final String ALLIANCE_KEY = "univ:12:alliance";
    private static final String MATCH_KEY = "univ:12:match";

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private AllianceRepository allianceRepository;
    @Mock private AllianceMatchRepository allianceMatchRepository;
    @Mock private Alliance alliance;
    @Mock private AllianceMatch match;

    private AllianceStandingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AllianceStandingResolver(stringRedisTemplate, allianceRepository, allianceMatchRepository);
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
    }

    private void givenCached(String key, String value) {
        given(valueOperations.get(key)).willReturn(value);
    }

    private void givenCurrentAllianceInDb() {
        given(allianceRepository.findCurrentByUniversityId(eq(UNIVERSITY_ID), any(OffsetDateTime.class)))
                .willReturn(Optional.of(alliance));
        given(alliance.getId()).willReturn(ALLIANCE_ID);
        given(alliance.getExpiredAt()).willReturn(OffsetDateTime.now().plusHours(24));
    }

    private void givenMatchInDb(Long allianceAId) {
        given(allianceMatchRepository.findByAllianceId(eq(ALLIANCE_ID), any(OffsetDateTime.class)))
                .willReturn(Optional.of(match));
        given(match.getId()).willReturn(MATCH_ID);
        given(match.getAllianceAId()).willReturn(allianceAId);
        given(match.getEndedAt()).willReturn(OffsetDateTime.now().plusHours(3));
    }

    // --- 연합 ---

    @Test
    @DisplayName("캐시도 DB도 연합이 없으면 \"none\"을 10초 캐시하고, 대결은 조회하지 않고 NONE을 돌려준다")
    void noAllianceCachesNoneAndSkipsMatch() {
        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(valueOperations).set(ALLIANCE_KEY, "none", Duration.ofSeconds(10));
        verify(allianceMatchRepository, never()).findByAllianceId(anyLong(), any());
        verify(valueOperations, never()).get(MATCH_KEY);
        assertThat(standing).isEqualTo(AllianceStanding.NONE);
    }

    @Test
    @DisplayName("연합 캐시가 \"none\"이면 DB를 조회하지 않는다")
    void noneAllianceCacheSkipsDb() {
        givenCached(ALLIANCE_KEY, "none");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(allianceRepository, never()).findCurrentByUniversityId(anyLong(), any());
        verify(allianceMatchRepository, never()).findByAllianceId(anyLong(), any());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(standing).isEqualTo(AllianceStanding.NONE);
    }

    @Test
    @DisplayName("연합 캐시가 없고 DB에 연합이 있으면 연합 만료 시각까지 캐시한다")
    void allianceFromDbIsCachedUntilExpiry() {
        givenCached(ALLIANCE_KEY, null);
        givenCurrentAllianceInDb();
        givenCached(MATCH_KEY, "none");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.captor();
        verify(valueOperations).set(eq(ALLIANCE_KEY), eq("7"), ttlCaptor.capture());
        // 만료까지 24시간. 테스트가 도는 동안 몇 초 줄어들 수 있다
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofHours(23), Duration.ofHours(24));
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 0L, ""));
    }

    @Test
    @DisplayName("연합 캐시가 있으면 연합 DB를 조회하지 않는다")
    void cachedAllianceSkipsDb() {
        givenCached(ALLIANCE_KEY, "7");
        givenCached(MATCH_KEY, "none");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(allianceRepository, never()).findCurrentByUniversityId(anyLong(), any());
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 0L, ""));
    }

    // --- 대결 ---

    @Test
    @DisplayName("연합 캐시가 있어도 대결 캐시가 없으면 대결을 DB에서 찾는다 (연합보다 나중에 시작한 대결)")
    void cachedAllianceStillLooksUpNewMatch() {
        givenCached(ALLIANCE_KEY, "7");
        givenMatchInDb(ALLIANCE_ID);

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(allianceMatchRepository).findByAllianceId(eq(ALLIANCE_ID), any(OffsetDateTime.class));
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 42L, "A"));
    }

    @Test
    @DisplayName("내 연합이 대결의 A면 \"{matchId}:A\"를 대결 종료 시각까지 캐시하고 A편을 돌려준다")
    void matchAsSideA() {
        givenCached(ALLIANCE_KEY, "7");
        givenMatchInDb(ALLIANCE_ID);

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.captor();
        verify(valueOperations).set(eq(MATCH_KEY), eq("42:A"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofHours(2), Duration.ofHours(3));
        assertThat(standing.team()).isEqualTo("A");
    }

    @Test
    @DisplayName("내 연합이 대결의 B면 \"{matchId}:B\"로 캐시하고 B편을 돌려준다")
    void matchAsSideB() {
        givenCached(ALLIANCE_KEY, "7");
        givenMatchInDb(OTHER_ALLIANCE_ID);

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(valueOperations).set(eq(MATCH_KEY), eq("42:B"), any(Duration.class));
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 42L, "B"));
    }

    @Test
    @DisplayName("연합은 있지만 진행 중 대결이 없으면 대결 캐시에 \"none\"을 10초 캐시하고 연합만 돌려준다")
    void allianceWithoutMatchCachesNone() {
        givenCached(ALLIANCE_KEY, "7");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(valueOperations).set(MATCH_KEY, "none", Duration.ofSeconds(10));
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 0L, ""));
    }

    @Test
    @DisplayName("대결 캐시가 있으면 대결 DB를 조회하지 않고 캐시의 대결·편을 쓴다")
    void cachedMatchSkipsDb() {
        givenCached(ALLIANCE_KEY, "7");
        givenCached(MATCH_KEY, "42:B");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(allianceMatchRepository, never()).findByAllianceId(anyLong(), any());
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 42L, "B"));
    }

    @Test
    @DisplayName("대결 캐시가 \"none\"이면 대결 DB를 조회하지 않는다")
    void noneMatchCacheSkipsDb() {
        givenCached(ALLIANCE_KEY, "7");
        givenCached(MATCH_KEY, "none");

        AllianceStanding standing = resolver.resolve(UNIVERSITY_ID);

        verify(allianceMatchRepository, never()).findByAllianceId(anyLong(), any());
        assertThat(standing).isEqualTo(new AllianceStanding(7L, 0L, ""));
    }
}
