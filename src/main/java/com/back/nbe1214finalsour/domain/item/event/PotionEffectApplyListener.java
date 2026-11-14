package com.back.nbe1214finalsour.domain.item.event;

import com.back.nbe1214finalsour.domain.item.repository.UserEffectCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PotionEffectApplyListener {

    private final UserEffectCache userEffectCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPotionPurchased(PotionPurchasedEvent event) {
        try {
            userEffectCache.apply(event.userId(), event.itemId(), event.multiplier(), event.expiredAt());
        } catch (RuntimeException e) {
            // 구매는 이미 커밋됐으므로 요청을 실패로 만들지 않는다. 효과가 적용되지 않은 채로 남으므로 로그로 감지한다
            log.error("물약 효과 적용 실패 userId={}, itemId={}, expiredAt={}",
                    event.userId(), event.itemId(), event.expiredAt(), e);
        }
    }
}
