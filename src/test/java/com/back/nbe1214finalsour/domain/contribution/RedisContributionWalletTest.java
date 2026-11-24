package com.back.nbe1214finalsour.domain.contribution;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
public class RedisContributionWalletTest {

    private static final Long USER_ID = 900_001L;
    private static final Long UNIVERSITY_ID = 900_001L;
    private static final String RANK_KEY = "rank:univ:" + UNIVERSITY_ID + ":user";

    @Autowired
    private RedisContributionWallet contributionWallet;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setup(){
        // 교내 기여도 순위 ZSet에 기여도 1000점인 유저
        redisTemplate.opsForZSet().add(RANK_KEY, USER_ID.toString(), 1000);
    }

    @AfterEach
    void tearDown() {
        // Redis는 @Transactional 롤백 대상이 아니라 직접 지운다
        redisTemplate.delete(RANK_KEY);
    }

    @Test
    @DisplayName("현재 기여도를 조회한다")
    void findBalance() {
        assertThat(contributionWallet.findBalance(USER_ID, UNIVERSITY_ID)).isEqualTo(1000L);
    }

    @Test
    @DisplayName("순위표에 없는 회원은 기여도 0")
    void findBalance_notInRank() {
        assertThat(contributionWallet.findBalance(999_999L, UNIVERSITY_ID)).isZero();
    }

}
