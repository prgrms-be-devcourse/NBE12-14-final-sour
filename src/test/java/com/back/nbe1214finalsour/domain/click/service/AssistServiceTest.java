package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceStandingResolver;
import com.back.nbe1214finalsour.domain.click.dto.AssistRequest;
import com.back.nbe1214finalsour.domain.click.dto.AssistResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 지원 검증 순서와 점수 계산 (SCRUM-187). Lua와 동맹 쿼리는 {@code AssistIntegrationTest}에서 실제로 확인한다.
 * 대상 학교 +floor(base * 0.3), 내 기여도 +floor(base * 아이템 배율), base = clickCount * clickPower.
 */
@ExtendWith(MockitoExtension.class)
class AssistServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long ALLY_ID = 15L;

    @Mock private ClickActionGuard clickActionGuard;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisHandler redisHandler;
    @Mock private UniversityCache universityCache;
    @Mock private AllianceAllyChecker allianceAllyChecker;
    @Mock private AllianceStandingResolver allianceStandingResolver;

    private AssistService assistService;

    @BeforeEach
    void setUp() {
        assistService = new AssistService(clickActionGuard, stringRedisTemplate, redisHandler, universityCache, allianceAllyChecker, allianceStandingResolver);
        // 연합 없음이 기본. 스크립트 전에 예외로 끝나는 테스트도 있어서 lenient로 둔다
        lenient().when(allianceStandingResolver.resolve(ALLY_ID))
                .thenReturn(AllianceStandingResolver.AllianceStanding.NONE);
    }

    private AssistRequest request(Long target, int clickCount) {
        return new AssistRequest("sub-1", clickCount, target);
    }

    @Test
    @DisplayName("내 학교를 지원하면 ASSIST_NOT_ALLY, 학교 조회와 회원 검증은 하지 않는다")
    void ownUniversity() {
        assertThatThrownBy(() -> assistService.assist(USER_ID, UNIVERSITY_ID, request(UNIVERSITY_ID, 3)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ASSIST_NOT_ALLY);
        verifyNoInteractions(universityCache, allianceAllyChecker, clickActionGuard, stringRedisTemplate);
    }

    @Test
    @DisplayName("없는 학교는 UNIV_NOT_FOUND, 동맹 확인과 요청 한도는 쓰지 않는다")
    void unknownTarget() {
        willThrow(new BusinessException(ErrorCode.UNIV_NOT_FOUND)).given(universityCache).getName(ALLY_ID);

        assertThatThrownBy(() -> assistService.assist(USER_ID, UNIVERSITY_ID, request(ALLY_ID, 3)))
                .extracting("errorCode").isEqualTo(ErrorCode.UNIV_NOT_FOUND);
        verifyNoInteractions(allianceAllyChecker, clickActionGuard);
    }

    @Test
    @DisplayName("같은 연합이 아니면 ASSIST_NOT_ALLY, 제출 ID와 요청 한도를 쓰지 않는다")
    void notAlly() {
        given(universityCache.getName(ALLY_ID)).willReturn("서울시립대학교");
        given(allianceAllyChecker.areAllies(UNIVERSITY_ID, ALLY_ID)).willReturn(false);

        assertThatThrownBy(() -> assistService.assist(USER_ID, UNIVERSITY_ID, request(ALLY_ID, 3)))
                .extracting("errorCode").isEqualTo(ErrorCode.ASSIST_NOT_ALLY);
        verify(clickActionGuard, never()).verifyAndGetBaseScore(anyLong(), anyString(), anyInt());
        verify(stringRedisTemplate, never()).execute(any(), anyList(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("대상 학교에는 0.3배(버림), 내 기여도에는 아이템 배율(버림)을 반영하고 응답에 서버 값을 담는다")
    void scoresAndResponse() {
        given(universityCache.getName(ALLY_ID)).willReturn("서울시립대학교");
        given(allianceAllyChecker.areAllies(UNIVERSITY_ID, ALLY_ID)).willReturn(true);
        // clickCount 7 × clickPower 3
        given(clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 7)).willReturn(21L);
        given(redisHandler.getHash(RedisKey.USER_EFFECT.of(USER_ID), "multiplier", Double.class)).willReturn(Optional.of(1.5));
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of("5402322", "41", "12928"));

        AssistResponse response = assistService.assist(USER_ID, UNIVERSITY_ID, request(ALLY_ID, 7));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.captor();
        ArgumentCaptor<Object> args = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keys.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly(RedisKey.RANK_UNIV.of(), RedisKey.RANK_USER.of(UNIVERSITY_ID),
                RedisKey.DIRTY_UNIV.of(), RedisKey.DIRTY_USER.of(),
                RedisKey.ALLIANCE_SCORE.of(0), RedisKey.MATCH_SCORE.of(0),
                RedisKey.DIRTY_ALLIANCE.of(), RedisKey.DIRTY_MATCH.of());
        // floor(21 * 0.3) = 6, floor(21 * 1.5) = 31
        assertThat(args.getAllValues()).containsExactly("15", "6", "1", "31", "0", "", "0");
        assertThat(response).isEqualTo(new AssistResponse(7L, 6L,
                new AssistResponse.AssistUniversityResponse(ALLY_ID, "서울시립대학교", 5402322L, 41L), 12928L));
    }

    // --- 연합·대결 점수 ---
    // 지원 점수는 대상 학교 점수이므로 연합·대결도 대상 학교(ALLY_ID)로 찾는다. 조회·캐시 자체는 AllianceStandingResolverTest에서 본다.

    private void givenAssistAllowed() {
        given(universityCache.getName(ALLY_ID)).willReturn("서울시립대학교");
        given(allianceAllyChecker.areAllies(UNIVERSITY_ID, ALLY_ID)).willReturn(true);
        given(clickActionGuard.verifyAndGetBaseScore(USER_ID, "sub-1", 7)).willReturn(21L);
        given(redisHandler.getHash(RedisKey.USER_EFFECT.of(USER_ID), "multiplier", Double.class)).willReturn(Optional.empty());
        given(stringRedisTemplate.<List>execute(any(), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(List.of("100", "3", "21"));
    }

    /** 스크립트에 넘긴 KEYS[5], KEYS[6]과 ARGV[5], ARGV[6] */
    private List<String> allianceKeysAndArgs() {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.captor();
        ArgumentCaptor<Object> args = ArgumentCaptor.captor();
        verify(stringRedisTemplate).execute(any(), keys.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture());
        return List.of(keys.getValue().get(4), keys.getValue().get(5),
                (String) args.getAllValues().get(4), (String) args.getAllValues().get(5));
    }

    @Test
    @DisplayName("내 학교가 아니라 대상 학교 ID로 연합·대결을 찾고, 결과를 KEYS[5·6]과 ARGV[5·6]으로 넘긴다")
    void resolvesStandingOfTargetAndPassesToScript() {
        givenAssistAllowed();
        given(allianceStandingResolver.resolve(ALLY_ID))
                .willReturn(new AllianceStandingResolver.AllianceStanding(7L, 42L, "B"));

        assistService.assist(USER_ID, UNIVERSITY_ID, request(ALLY_ID, 7));

        verify(allianceStandingResolver).resolve(ALLY_ID);
        verify(allianceStandingResolver, never()).resolve(UNIVERSITY_ID);
        assertThat(allianceKeysAndArgs()).containsExactly("alliance:7:score", "match:42:score", "7", "B");
    }

    @Test
    @DisplayName("대상 학교에 연합이 없으면(NONE) 0 키와 \"0\", \"\"를 넘겨 스크립트가 연합·대결 점수를 건너뛰게 한다")
    void noAlliancePassesZeroKeys() {
        givenAssistAllowed();

        assistService.assist(USER_ID, UNIVERSITY_ID, request(ALLY_ID, 7));

        assertThat(allianceKeysAndArgs()).containsExactly("alliance:0:score", "match:0:score", "0", "");
    }
}
