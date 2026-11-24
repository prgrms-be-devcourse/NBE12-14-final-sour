package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.repository.UserEffect;
import com.back.nbe1214finalsour.domain.item.repository.UserEffectCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ActiveEffectServiceTest {

    @Mock
    private UserEffectCache userEffectCache;

    @Mock
    private ItemService itemService;

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private ActiveEffectService activeEffectService;

    private static final Long USER_ID = 1L;

    private static final List<Item> ITEMS = List.of(
            new Item(1L, "2배 물약", "1시간 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 500L, 3600, 2.0),
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600, 4.0)
    );

    // 지금으로부터 millis 뒤에 끝나는 효과가 Redis에 있음
    private OffsetDateTime givenEffect(Long itemId, double multiplier, long millis) {
        OffsetDateTime expiredAt = NOW.plusMillis(millis).atOffset(ZoneOffset.UTC);
        given(this.userEffectCache.find(USER_ID))
                .willReturn(Optional.of(new UserEffect(itemId, multiplier, expiredAt)));
        return expiredAt;
    }

    @Test
    @DisplayName("Redis에 효과가 없으면 empty, 아이템 목록도 보지 않는다")
    void findActive_noEffect() {
        given(this.userEffectCache.find(USER_ID)).willReturn(Optional.empty());

        assertThat(this.activeEffectService.findActive(USER_ID)).isEmpty();
        verifyNoInteractions(this.itemService);
    }

    @Test
    @DisplayName("효과가 있으면 아이템 이름·배율·만료 시각·남은 시간(초)을 돌려준다")
    void findActive_returnsEffect() {
        OffsetDateTime expiredAt = givenEffect(2L, 4.0, 412_000);
        given(this.itemService.getItems()).willReturn(ITEMS);

        Optional<ActiveEffect> result = this.activeEffectService.findActive(USER_ID);

        assertThat(result).isPresent();
        ActiveEffect effect = result.get();
        assertThat(effect.itemId()).isEqualTo(2L);
        assertThat(effect.name()).isEqualTo("4배 물약");
        assertThat(effect.multiplier()).isEqualTo(4.0);
        assertThat(effect.expiredAt()).isAtSameInstantAs(expiredAt);
        assertThat(effect.remainingSeconds()).isEqualTo(412L);
    }

    @Test
    @DisplayName("남은 시간은 올림한다 (0.4초 남아도 1초, 효과가 살아 있는 동안 0이 나오지 않음)")
    void findActive_remainingSecondsRoundsUp() {
        givenEffect(2L, 4.0, 400);
        given(this.itemService.getItems()).willReturn(ITEMS);

        assertThat(this.activeEffectService.findActive(USER_ID))
                .get()
                .extracting(ActiveEffect::remainingSeconds)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("만료 시각이 지금이거나 지났으면 키가 남아 있어도 empty (앱 서버와 Redis 시계 차이 방어)")
    void findActive_expired() {
        givenEffect(2L, 4.0, 0);

        assertThat(this.activeEffectService.findActive(USER_ID)).isEmpty();
        verifyNoInteractions(this.itemService);
    }

    @Test
    @DisplayName("아이템 목록에 없는 itemId면 이름만 null이고 나머지는 그대로 돌려준다")
    void findActive_unknownItem() {
        givenEffect(99L, 3.0, 60_000);
        given(this.itemService.getItems()).willReturn(ITEMS);

        Optional<ActiveEffect> result = this.activeEffectService.findActive(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isNull();
        assertThat(result.get().multiplier()).isEqualTo(3.0);
        assertThat(result.get().remainingSeconds()).isEqualTo(60L);
    }
}