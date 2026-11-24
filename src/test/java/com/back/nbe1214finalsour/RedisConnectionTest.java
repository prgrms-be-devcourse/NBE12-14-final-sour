package com.back.nbe1214finalsour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class RedisConnectionTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("Redis에 값을 쓰고 읽을 수 있다")
    void writeAndRead() {
        stringRedisTemplate.opsForValue().set("ci:test", "ok");

        assertThat(stringRedisTemplate.opsForValue().get("ci:test")).isEqualTo("ok");
    }

    @Test
    @DisplayName("INCRBY로 값이 누적된다")
    void incrementBy() {
        stringRedisTemplate.delete("ci:counter");

        stringRedisTemplate.opsForValue().increment("ci:counter", 3);
        stringRedisTemplate.opsForValue().increment("ci:counter", 5);

        assertThat(stringRedisTemplate.opsForValue().get("ci:counter")).isEqualTo("8");
    }
}