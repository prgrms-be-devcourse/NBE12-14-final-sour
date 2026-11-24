package com.back.nbe1214finalsour.global.redis;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lua 동작에 대한 가정을 실제 Redis로 확인하는 학습 테스트. */
@IntegrationTest
class RedisLuaLearningTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String EFFECT_KEY = RedisKey.USER_EFFECT.of(900_31L);

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(EFFECT_KEY);
    }

    @SuppressWarnings("rawtypes")
    private List run(String lua) {
        return run(lua, List.of());
    }

    @SuppressWarnings("rawtypes")
    private List run(String lua, List<String> keys) {
        return stringRedisTemplate.execute(new DefaultRedisScript<>(lua, List.class), keys);
    }

    @Test
    @DisplayName("Lua가 숫자와 문자열이 섞인 테이블을 반환하면 RedisScript<List>는 Long과 String으로 변환한다")
    @SuppressWarnings("rawtypes")
    void luaTableReturn_mixedTypes() {
        // given
        String withEffect = "return {1000, '{\"itemId\":\"1\"}'}";
        String withoutEffect = "return {1000, ''}";
        String failure = "return {-1}";

        // when
        List first = run(withEffect);
        List second = run(withoutEffect);
        List third = run(failure);

        // then
        assertThat(first).containsExactly(1000L, "{\"itemId\":\"1\"}");
        assertThat(second).containsExactly(1000L, "");
        assertThat(third).containsExactly(-1L);
    }

    @Test
    @DisplayName("Lua에서 지난 시각으로 PEXPIREAT하면 키가 바로 지워진다")
    @SuppressWarnings("rawtypes")
    void pexpireatPast_deletesKeyImmediately() {
        // given
        String lua = """
                redis.call('HSET', KEYS[1], 'itemId', '1')
                local expired = redis.call('PEXPIREAT', KEYS[1], 1000)
                local exists = redis.call('EXISTS', KEYS[1])
                return {expired, exists}
                """;

        // when
        List result = run(lua, List.of(EFFECT_KEY));

        // then
        assertThat(result).containsExactly(1L, 0L);
        assertThat(stringRedisTemplate.hasKey(EFFECT_KEY)).isFalse();
    }
}
