package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.repository.UserEffectCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ActiveEffectService {

    private final UserEffectCache userEffectCache;
    private final ItemService itemService;
    private final Clock clock;

    public Optional<ActiveEffect> findActive(Long userId) {
        Instant now = Instant.now(this.clock);

        return this.userEffectCache.find(userId)
                // 키는 만료 시각에 Redis가 지우지만, 앱 서버와 Redis 시계가 어긋날 수 있어 한 번 더 확인
                .filter(effect -> effect.expiredAt().toInstant().isAfter(now))
                .map(effect -> new ActiveEffect(
                        effect.itemId(),
                        findItemName(effect.itemId()),
                        effect.multiplier(),
                        effect.expiredAt(),
                        remainingSeconds(now, effect.expiredAt())
                ));
    }

    private String findItemName(Long itemId) {
        return this.itemService.getItems().stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .map(Item::getName)
                .orElse(null);
    }

    // 남은 시간(초)을 올림한다. 0.4초 남아도 1초라서, 효과가 살아 있는 동안 0이 나오지 않는다
    private static long remainingSeconds(Instant now, OffsetDateTime expiredAt) {
        long millis = Duration.between(now, expiredAt.toInstant()).toMillis();
        return (millis + 999) / 1000;
    }
}
