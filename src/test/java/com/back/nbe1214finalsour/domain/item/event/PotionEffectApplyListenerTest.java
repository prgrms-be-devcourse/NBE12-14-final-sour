package com.back.nbe1214finalsour.domain.item.event;


import com.back.nbe1214finalsour.domain.item.repository.UserEffectCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class PotionEffectApplyListenerTest {

    private static final OffsetDateTime EXPIRED_AT = OffsetDateTime.of(2026, 11, 14, 4, 0, 0, 0, ZoneOffset.UTC);
    private static final PotionPurchasedEvent EVENT = new PotionPurchasedEvent(1L, 2L, 4.0, EXPIRED_AT);

    @Mock
    private UserEffectCache userEffectCache;

    @InjectMocks
    private PotionEffectApplyListener listener;

    @Test
    @DisplayName("물약 구매 이벤트를 받으면 회원 효과를 Redis에 적용")
    void onPotionPurchased() {
        listener.onPotionPurchased(EVENT);

        verify(userEffectCache).apply(1L, 2L, 4.0, EXPIRED_AT);
    }

    @Test
    @DisplayName("Redis 적용이 실패해도 예외를 밖으로 던지지 않음 (이미 커밋된 구매가 500이 되지 않도록)")
    void onPotionPurchased_redisFailure() {
        willThrow(new RedisConnectionFailureException("Redis 연결 실패"))
                .given(userEffectCache).apply(1L, 2L, 4.0, EXPIRED_AT);

        assertThatCode(() -> listener.onPotionPurchased(EVENT))
                .doesNotThrowAnyException();
    }

}
