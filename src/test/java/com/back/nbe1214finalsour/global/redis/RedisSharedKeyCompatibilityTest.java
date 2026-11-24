package com.back.nbe1214finalsour.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 여러 팀이 같이 쓰는 키({@code rank:*} ZSet, {@code dirty:*} Set)의 member 직렬화 호환성.
 *
 * <p>팀 합의: member는 평문 숫자 문자열로 통일한다. 이 테스트는 그 합의가 지켜질 때 호환되고,
 * 깨질 때 <b>예외 없이 조용히</b> 멤버를 못 찾는다는 것을 고정한다.
 */
@IntegrationTest
class RedisSharedKeyCompatibilityTest {

    private static final long USER_ID = 1024L;
    private static final String RANK_KEY = RedisKey.RANK_USER.of(7);
    private static final String DIRTY_KEY = RedisKey.DIRTY_USER.of();

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clear() {
        redisTemplate.delete(List.of(RANK_KEY, DIRTY_KEY));
    }

    // --- ZSet member: 합의를 지킨 경우 ---

    @Test
    @DisplayName("RedisTemplate으로 Long member를 넣으면 평문 숫자로 저장되어 StringRedisTemplate과 호환된다")
    void zsetLongMemberIsPlainNumberAndInteroperable() {
        redisTemplate.opsForZSet().add(RANK_KEY, USER_ID, 10.0);

        // 실제로 저장된 member 바이트에 따옴표가 없다
        assertThat(stringRedisTemplate.opsForZSet().range(RANK_KEY, 0, -1)).containsExactly("1024");
        // 지갑 쪽(StringRedisTemplate)에서 찾을 수 있다
        assertThat(stringRedisTemplate.opsForZSet().score(RANK_KEY, "1024")).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Integer member도 평문 숫자로 저장된다")
    void zsetIntegerMemberIsPlainNumber() {
        redisTemplate.opsForZSet().add(RANK_KEY, 1024, 10.0);

        assertThat(stringRedisTemplate.opsForZSet().range(RANK_KEY, 0, -1)).containsExactly("1024");
    }

    @Test
    @DisplayName("StringRedisTemplate으로 넣은 member는 RedisTemplate의 Long 조회와도 호환된다 (역방향)")
    void zsetPlainMemberIsFoundByLongLookup() {
        stringRedisTemplate.opsForZSet().add(RANK_KEY, "1024", 10.0);

        assertThat(redisTemplate.opsForZSet().score(RANK_KEY, USER_ID)).isEqualTo(10.0);
    }

    // --- ZSet member: 합의를 깬 경우 (조용한 버그) ---

    @Test
    @DisplayName("RedisTemplate으로 String member를 넣으면 따옴표가 붙어 지갑이 멤버를 못 찾는다")
    void zsetStringMemberIsQuotedAndSilentlyMissed() {
        redisTemplate.opsForZSet().add(RANK_KEY, "1024", 10.0);

        // member가 "\"1024\""로 저장된다
        assertThat(stringRedisTemplate.opsForZSet().range(RANK_KEY, 0, -1)).containsExactly("\"1024\"");

        // 예외가 아니라 null이 돌아온다 — 잔액 부족으로 오인되는 지점
        assertThat(stringRedisTemplate.opsForZSet().score(RANK_KEY, "1024")).isNull();
        assertThat(stringRedisTemplate.opsForZSet().rank(RANK_KEY, "1024")).isNull();

        // 같은 키에 Long으로 또 넣으면 한 유저가 member 2개로 갈린다
        redisTemplate.opsForZSet().add(RANK_KEY, USER_ID, 20.0);
        assertThat(redisTemplate.opsForZSet().zCard(RANK_KEY)).isEqualTo(2L);
    }

    // --- Set member: 동일한 규칙이 적용된다 ---

    @Test
    @DisplayName("dirty Set도 Long member는 평문으로 저장되어 호환된다")
    void setLongMemberIsPlainNumberAndInteroperable() {
        redisTemplate.opsForSet().add(DIRTY_KEY, USER_ID);

        assertThat(stringRedisTemplate.opsForSet().members(DIRTY_KEY)).containsExactly("1024");
        assertThat(stringRedisTemplate.opsForSet().isMember(DIRTY_KEY, "1024")).isTrue();
    }

    @Test
    @DisplayName("dirty Set에 String member를 넣으면 따옴표가 붙어 배치가 ID를 못 읽는다")
    void setStringMemberIsQuotedAndSilentlyMissed() {
        redisTemplate.opsForSet().add(DIRTY_KEY, "1024");

        assertThat(stringRedisTemplate.opsForSet().members(DIRTY_KEY)).containsExactly("\"1024\"");
        assertThat(stringRedisTemplate.opsForSet().isMember(DIRTY_KEY, "1024")).isFalse();
    }
}
