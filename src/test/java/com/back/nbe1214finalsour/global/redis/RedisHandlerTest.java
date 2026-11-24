package com.back.nbe1214finalsour.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@IntegrationTest
class RedisHandlerTest {

    private static final String HASH_KEY = "handler:hash";
    private static final String TTL_KEY = "handler:ttl";
    private static final String COUNTER_KEY = "handler:counter";
    private static final String FLAG_KEY = "handler:flag";
    private static final String MIX_KEY = "handler:mix";
    private static final String RANK_KEY = RedisKey.RANK_USER.of(7);
    private static final String DIRTY_KEY = RedisKey.DIRTY_USER.of();
    private static final String EFFECT_KEY = RedisKey.USER_EFFECT.of(1);

    @Autowired
    private RedisHandler redisHandler;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clear() {
        redisTemplate.delete(List.of(HASH_KEY, TTL_KEY, COUNTER_KEY, FLAG_KEY, MIX_KEY, RANK_KEY, DIRTY_KEY, EFFECT_KEY));
    }

    @Test
    @DisplayName("Hash 필드에 객체를 쓰고 읽을 수 있다")
    void setHashAndGetHash() {
        LocalDateTime now = LocalDateTime.now();
        TempPost tempPost = new TempPost("제목", "내용", now);

        redisHandler.setHash(HASH_KEY, "post1", tempPost);
        TempPost findTempPost = redisHandler.getHash(HASH_KEY, "post1", TempPost.class).get();

        assertThat(findTempPost.title()).isEqualTo("제목");
        assertThat(findTempPost.content()).isEqualTo("내용");
        assertThat(findTempPost.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("없는 Hash 필드를 조회하면 Optional.empty")
    void getHashReturnsEmptyWhenFieldMissing() {
        assertThat(redisHandler.getHash(HASH_KEY, "없는필드", TempPost.class)).isEmpty();
    }

    @Test
    @DisplayName("setTtl로 저장한 키에는 만료가 걸린다")
    void setTtlAppliesExpiration() {
        redisHandler.setTtl(TTL_KEY, new TempPost("제목", "내용", LocalDateTime.now()), Duration.ofMinutes(10));

        assertThat(redisHandler.get(TTL_KEY, TempPost.class)).isPresent();
        assertThat(redisTemplate.getExpire(TTL_KEY))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    @Test
    @DisplayName("set으로 저장한 키에는 만료가 없다")
    void setDoesNotApplyExpiration() {
        redisHandler.set(TTL_KEY, new TempPost("제목", "내용", LocalDateTime.now()));

        // Redis는 만료가 없는 키의 TTL을 -1로 응답한다
        assertThat(redisTemplate.getExpire(TTL_KEY)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("setHashFieldTtl은 필드를 저장하면서 필드 단위 만료를 건다")
    void setHashFieldTtlAppliesExpirationPerField() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> fields = Map.of("post1", new TempPost("제목", "내용", now));

        redisHandler.setHashFieldTtl(HASH_KEY, fields, Duration.ofMinutes(10));

        assertThat(redisHandler.getHash(HASH_KEY, "post1", TempPost.class).get().title()).isEqualTo("제목");

        HashOperations<String, String, Object> hashOperations = redisTemplate.opsForHash();
        Duration ttl = hashOperations.getTimeToLive(HASH_KEY, List.of("post1")).ttlOf("post1");

        assertThat(ttl).isNotNull()
                .isGreaterThan(Duration.ZERO)
                .isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("setHash로만 저장한 필드에는 만료가 없다")
    void setHashDoesNotApplyExpiration() {
        redisHandler.setHash(HASH_KEY, "post1", new TempPost("제목", "내용", LocalDateTime.now()));

        HashOperations<String, String, Object> hashOperations = redisTemplate.opsForHash();

        assertThat(hashOperations.getTimeToLive(HASH_KEY, List.of("post1")).persistent())
                .containsExactly("post1");
    }

    // --- 원자 연산 ---

    @Test
    @DisplayName("increment는 키가 없으면 0에서 시작해 1을 반환한다")
    void incrementCreatesKeyFromZero() {
        assertThat(redisHandler.increment(COUNTER_KEY)).isEqualTo(1L);
        assertThat(redisHandler.increment(COUNTER_KEY)).isEqualTo(2L);
    }

    @Test
    @DisplayName("increment로 만든 카운터는 따옴표 없는 숫자로 저장된다")
    void incrementStoresPlainNumber() {
        redisHandler.increment(COUNTER_KEY);

        assertThat(stringRedisTemplate.opsForValue().get(COUNTER_KEY)).isEqualTo("1");
        assertThat(redisHandler.get(COUNTER_KEY, Long.class)).contains(1L);
    }

    @Test
    @DisplayName("incrementWithTtl은 최초 증가에만 TTL을 걸고 이후 호출은 갱신하지 않는다")
    void incrementWithTtlAppliesTtlOnlyOnFirstIncrement() {
        assertThat(redisHandler.incrementWithTtl(COUNTER_KEY, Duration.ofMinutes(10))).isEqualTo(1L);

        Long firstTtl = redisTemplate.getExpire(COUNTER_KEY);
        assertThat(firstTtl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());

        // TTL을 더 길게 넘겨도 두 번째 증가에서는 다시 걸지 않는다
        assertThat(redisHandler.incrementWithTtl(COUNTER_KEY, Duration.ofMinutes(60))).isEqualTo(2L);
        assertThat(redisTemplate.getExpire(COUNTER_KEY)).isLessThanOrEqualTo(firstTtl);
    }

    @Test
    @DisplayName("incrementHash는 Hash 필드를 HINCRBY로 증가시킨다")
    void incrementHashIncreasesField() {
        assertThat(redisHandler.incrementHash(HASH_KEY, "count", 3L)).isEqualTo(3L);
        assertThat(redisHandler.incrementHash(HASH_KEY, "count", 2L)).isEqualTo(5L);
        assertThat(redisHandler.getHash(HASH_KEY, "count", Long.class)).contains(5L);
    }

    @Test
    @DisplayName("setIfAbsent는 처음에만 true를 반환하고 TTL을 건다")
    void setIfAbsentOnlySucceedsOnce() {
        assertThat(redisHandler.setIfAbsent(FLAG_KEY, "sent", Duration.ofMinutes(5))).isTrue();
        assertThat(redisHandler.setIfAbsent(FLAG_KEY, "sent", Duration.ofMinutes(5))).isFalse();

        assertThat(redisTemplate.getExpire(FLAG_KEY))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(5).toSeconds());
    }

    @Test
    @DisplayName("delete는 키가 있으면 true, 없으면 false를 반환한다")
    void deleteReturnsWhetherKeyExisted() {
        redisHandler.set(FLAG_KEY, "sent");

        assertThat(redisHandler.delete(FLAG_KEY)).isTrue();
        assertThat(redisHandler.delete(FLAG_KEY)).isFalse();
    }

    @Test
    @DisplayName("expire는 키가 있으면 true, 없으면 false를 반환한다")
    void expireReturnsWhetherKeyExisted() {
        redisHandler.set(FLAG_KEY, "sent");

        assertThat(redisHandler.expire(FLAG_KEY, Duration.ofMinutes(5))).isTrue();
        assertThat(redisTemplate.getExpire(FLAG_KEY)).isPositive();

        redisHandler.delete(FLAG_KEY);
        assertThat(redisHandler.expire(FLAG_KEY, Duration.ofMinutes(5))).isFalse();
    }

    // --- 직렬화 함정: 카운터를 set으로 초기화하면 안 되는 이유 ---

    @Test
    @DisplayName("set으로 숫자 타입을 저장하면 increment가 동작한다")
    void incrementWorksWhenValueStoredAsNumber() {
        redisHandler.set(COUNTER_KEY, 1);

        assertThat(stringRedisTemplate.opsForValue().get(COUNTER_KEY)).isEqualTo("1");
        assertThat(redisHandler.increment(COUNTER_KEY)).isEqualTo(2L);
        assertThat(redisHandler.get(COUNTER_KEY, Long.class)).contains(2L);
    }

    @Test
    @DisplayName("set으로 숫자를 문자열로 저장하면 따옴표가 붙어 increment가 실패한다")
    void incrementFailsWhenValueStoredAsString() {
        redisHandler.set(COUNTER_KEY, "1");

        assertThat(stringRedisTemplate.opsForValue().get(COUNTER_KEY)).isEqualTo("\"1\"");
        assertThatThrownBy(() -> redisHandler.increment(COUNTER_KEY))
                .isInstanceOf(RedisSystemException.class);
    }

    @Test
    @DisplayName("setHash로 숫자를 문자열로 저장한 필드는 incrementHash가 실패한다")
    void incrementHashFailsWhenFieldStoredAsString() {
        redisHandler.setHash(HASH_KEY, "count", "1");

        assertThatThrownBy(() -> redisHandler.incrementHash(HASH_KEY, "count", 1L))
                .isInstanceOf(RedisSystemException.class);
    }

    // --- 직렬화 함정: 한 키를 두 템플릿으로 섞어 쓰면 안 되는 이유 ---

    @Test
    @DisplayName("RedisHandler로 쓴 문자열은 StringRedisTemplate에서 따옴표가 붙어 읽힌다")
    void handlerWrittenStringIsQuotedForStringRedisTemplate() {
        redisHandler.set(MIX_KEY, "abc");

        assertThat(stringRedisTemplate.opsForValue().get(MIX_KEY)).isEqualTo("\"abc\"");
    }

    @Test
    @DisplayName("StringRedisTemplate으로 쓴 문자열은 RedisHandler.get에서 역직렬화에 실패한다")
    void stringTemplateWrittenStringFailsHandlerGet() {
        stringRedisTemplate.opsForValue().set(MIX_KEY, "abc");

        // 따옴표 없는 abc는 유효한 JSON이 아니다
        assertThatThrownBy(() -> redisHandler.get(MIX_KEY, String.class))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    @DisplayName("StringRedisTemplate으로 쓴 숫자는 유효한 JSON이라 RedisHandler.get으로 읽힌다")
    void stringTemplateWrittenNumberIsReadableByHandler() {
        stringRedisTemplate.opsForValue().set(MIX_KEY, "42");

        assertThat(redisHandler.get(MIX_KEY, Long.class)).contains(42L);
    }

    // --- ZSet (rank:*) : member는 평문 숫자로만 저장된다 ---

    @Test
    @DisplayName("addRank로 넣은 member는 평문 숫자라 StringRedisTemplate에서 찾을 수 있다")
    void addRankStoresPlainNumberMember() {
        redisHandler.addRank(RANK_KEY, 1024L, 10.0);

        assertThat(stringRedisTemplate.opsForZSet().range(RANK_KEY, 0, -1)).containsExactly("1024");
        assertThat(stringRedisTemplate.opsForZSet().score(RANK_KEY, "1024")).isEqualTo(10.0);
    }

    @Test
    @DisplayName("incrementRankScore는 점수를 누적하고 누적값을 반환한다")
    void incrementRankScoreAccumulates() {
        assertThat(redisHandler.incrementRankScore(RANK_KEY, 1024L, 10.0)).isEqualTo(10.0);
        assertThat(redisHandler.incrementRankScore(RANK_KEY, 1024L, 2.5)).isEqualTo(12.5);

        assertThat(stringRedisTemplate.opsForZSet().score(RANK_KEY, "1024")).isEqualTo(12.5);
    }

    @Test
    @DisplayName("getRankScore는 없는 멤버에 대해 empty를 반환한다")
    void getRankScoreReturnsEmptyWhenMemberMissing() {
        redisHandler.addRank(RANK_KEY, 1024L, 10.0);

        assertThat(redisHandler.getRankScore(RANK_KEY, 1024L)).hasValue(10.0);
        assertThat(redisHandler.getRankScore(RANK_KEY, 9999L)).isEmpty();
    }

    @Test
    @DisplayName("getRankPosition은 점수 높은 순으로 0부터 순위를 센다")
    void getRankPositionCountsFromHighestScore() {
        redisHandler.addRank(RANK_KEY, 1L, 30.0);
        redisHandler.addRank(RANK_KEY, 2L, 20.0);
        redisHandler.addRank(RANK_KEY, 3L, 10.0);

        assertThat(redisHandler.getRankPosition(RANK_KEY, 1L)).hasValue(0L);
        assertThat(redisHandler.getRankPosition(RANK_KEY, 3L)).hasValue(2L);
        assertThat(redisHandler.getRankPosition(RANK_KEY, 9999L)).isEmpty();
    }

    @Test
    @DisplayName("getTopRank는 점수 높은 순으로 count개를 반환한다")
    void getTopRankReturnsHighestScoresFirst() {
        redisHandler.addRank(RANK_KEY, 1L, 10.0);
        redisHandler.addRank(RANK_KEY, 2L, 30.0);
        redisHandler.addRank(RANK_KEY, 3L, 20.0);

        assertThat(redisHandler.getTopRank(RANK_KEY, 2))
                .containsExactly(
                        new RedisHandler.RankEntry(2L, 30.0),
                        new RedisHandler.RankEntry(3L, 20.0)
                );
    }

    @Test
    @DisplayName("getTopRank에 0 이하를 넘기면 빈 목록을 반환한다")
    void getTopRankReturnsEmptyWhenCountNotPositive() {
        redisHandler.addRank(RANK_KEY, 1L, 10.0);

        // count - 1 이 -1이 되어 전체가 조회되는 것을 막는다
        assertThat(redisHandler.getTopRank(RANK_KEY, 0)).isEmpty();
        assertThat(redisHandler.getTopRank(RANK_KEY, -5)).isEmpty();
    }

    @Test
    @DisplayName("removeRank는 멤버가 있으면 true, 없으면 false를 반환한다")
    void removeRankReturnsWhetherMemberExisted() {
        redisHandler.addRank(RANK_KEY, 1024L, 10.0);

        assertThat(redisHandler.removeRank(RANK_KEY, 1024L)).isTrue();
        assertThat(redisHandler.removeRank(RANK_KEY, 1024L)).isFalse();
    }

    // --- Set (dirty:*) ---

    @Test
    @DisplayName("addDirty는 새 ID면 true, 이미 있으면 false를 반환한다")
    void addDirtyDeduplicates() {
        assertThat(redisHandler.addDirty(DIRTY_KEY, 1024L)).isTrue();
        assertThat(redisHandler.addDirty(DIRTY_KEY, 1024L)).isFalse();

        assertThat(stringRedisTemplate.opsForSet().members(DIRTY_KEY)).containsExactly("1024");
    }

    @Test
    @DisplayName("getDirtyMembers는 저장된 ID를 Long으로 돌려준다")
    void getDirtyMembersReturnsLongs() {
        redisHandler.addDirty(DIRTY_KEY, 1L);
        redisHandler.addDirty(DIRTY_KEY, 2L);

        assertThat(redisHandler.getDirtyMembers(DIRTY_KEY)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(redisHandler.getDirtyMembers("handler:없는키")).isEmpty();
    }

    @Test
    @DisplayName("removeDirty는 ID가 있으면 true, 없으면 false를 반환한다")
    void removeDirtyReturnsWhetherMemberExisted() {
        redisHandler.addDirty(DIRTY_KEY, 1024L);

        assertThat(redisHandler.removeDirty(DIRTY_KEY, 1024L)).isTrue();
        assertThat(redisHandler.removeDirty(DIRTY_KEY, 1024L)).isFalse();
    }

    // --- 읽기 쪽 가드: 다른 직렬화로 쓴 member가 섞이면 조용히 틀리지 않고 터진다 ---

    @Test
    @DisplayName("따옴표 붙은 ZSet member를 읽으면 COMMON_REDIS_MEMBER_NOT_NUMERIC으로 터진다")
    void getTopRankFailsLoudlyOnQuotedMember() {
        // 다른 팀이 RedisTemplate에 String member를 넣은 상황
        redisTemplate.opsForZSet().add(RANK_KEY, "1024", 10.0);

        assertThatThrownBy(() -> redisHandler.getTopRank(RANK_KEY, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("평문 숫자가 아닙니다")
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC);
    }

    @Test
    @DisplayName("따옴표 붙은 Set member를 읽으면 COMMON_REDIS_MEMBER_NOT_NUMERIC으로 터진다")
    void getDirtyMembersFailsLoudlyOnQuotedMember() {
        redisTemplate.opsForSet().add(DIRTY_KEY, "1024");

        assertThatThrownBy(() -> redisHandler.getDirtyMembers(DIRTY_KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("평문 숫자가 아닙니다")
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC);
    }

    @Test
    @DisplayName("COMMON_REDIS_MEMBER_NOT_NUMERIC의 상세 메시지는 로그용이고 응답 메시지로는 나가지 않는다")
    void redisMemberErrorDetailIsNotExposedInResponseMessage() {
        assertThat(ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorCode.COMMON_REDIS_MEMBER_NOT_NUMERIC.getMessage()).doesNotContain("member", "직렬화");
    }

    // --- 아이템 배율(user:{userId}:effect): 어느 쪽이 써도 Double로 읽혀야 한다 ---
    // ClickService가 이 경로로 multiplier를 읽는다. 쓰는 팀이 숫자/문자열 중 무엇을 쓸지 알 수 없다

    @Test
    @DisplayName("RedisHandler가 숫자로 쓴 multiplier를 Double로 읽는다")
    void getHashReadsMultiplierWrittenAsNumber() {
        redisHandler.setHash(EFFECT_KEY, "multiplier", 1.5);

        assertThat(stringRedisTemplate.<String, String>opsForHash().get(EFFECT_KEY, "multiplier"))
                .isEqualTo("1.5");
        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).contains(1.5);
    }

    @Test
    @DisplayName("RedisHandler가 문자열로 쓴 multiplier도 Double로 읽는다 (따옴표가 붙어도 강제 변환)")
    void getHashReadsMultiplierWrittenAsQuotedString() {
        redisHandler.setHash(EFFECT_KEY, "multiplier", "1.5");

        // 실제 저장된 값에는 따옴표가 붙는다
        assertThat(stringRedisTemplate.<String, String>opsForHash().get(EFFECT_KEY, "multiplier"))
                .isEqualTo("\"1.5\"");
        // 그래도 Double로 읽힌다 (Double.parseDouble은 여기서 터졌다)
        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).contains(1.5);
    }

    @Test
    @DisplayName("StringRedisTemplate으로 쓴 multiplier도 Double로 읽는다")
    void getHashReadsMultiplierWrittenByStringTemplate() {
        stringRedisTemplate.<String, String>opsForHash().put(EFFECT_KEY, "multiplier", "1.5");

        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).contains(1.5);
    }

    @Test
    @DisplayName("정수로 쓴 multiplier도 Double로 읽는다")
    void getHashReadsMultiplierWrittenAsInteger() {
        redisHandler.setHash(EFFECT_KEY, "multiplier", 2);

        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).contains(2.0);
    }

    @Test
    @DisplayName("multiplier가 없으면 empty를 반환한다 (호출부가 1.0으로 대체한다)")
    void getHashReturnsEmptyWhenMultiplierMissing() {
        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).isEmpty();
    }

    @Test
    @DisplayName("숫자가 아닌 multiplier는 터진다")
    void getHashFailsWhenMultiplierNotNumeric() {
        stringRedisTemplate.<String, String>opsForHash().put(EFFECT_KEY, "multiplier", "abc");

        assertThatThrownBy(() -> redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class))
                .isInstanceOf(RuntimeException.class);
    }

    record TempPost(
            String title,
            String content,
            LocalDateTime createdAt
    ) {}
}
