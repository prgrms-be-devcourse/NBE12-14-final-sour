package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.repository.PotionPurchaseOutcome;
import com.back.nbe1214finalsour.domain.item.repository.RedisPotionPurchaser;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// 기여도 아이템(물약) 구매
// 구매와 효과 적용은 Redis Lua가 하고, DB 이력 저장은 PotionPurchaseRecorder가 자기 트랜잭션으로 한다
@Slf4j
@Service
@RequiredArgsConstructor
public class ContributionPurchaseService {

    private final CreditWallet creditWallet;
    private final RedisPotionPurchaser redisPotionPurchaser;
    private final PotionPurchaseRecorder potionPurchaseRecorder;
    private final Clock clock;

    // 트랜잭션을 붙이지 말 것: 붙이면 record()가 합류해 커밋이 try 밖으로 밀리고 커밋 실패를 보상하지 못한다
    public ItemPurchaseResult purchase(Long userId, Long universityId, Item item, UUID idempotencyKey) {
        // 만료 시각은 한 번만 계산해 Lua와 구매 이력에 같은 값을 쓴다
        // Redis PEXPIREAT은 밀리초, DB timestamptz는 마이크로초라 밀리초로 잘라야 두 곳의 값이 같다
        OffsetDateTime expiredAt = OffsetDateTime.now(this.clock)
                .plusSeconds(item.getDuration())
                .truncatedTo(ChronoUnit.MILLIS);

        // Redis 구매 Lua: 기여도 차감 + 효과 적용을 한 번에
        PotionPurchaseOutcome outcome = this.redisPotionPurchaser.purchase(
                userId, universityId, item.getId(), item.getPrice(), item.getMultiplier(), expiredAt, idempotencyKey);
        if (outcome.code() == -1) {
            throw new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_SCORE); // 잔여 기여도가 부족하다면 차감없이 400
        }
        if (outcome.code() == -2) {
            throw new BusinessException(ErrorCode.ITEM_DUPLICATE_PURCHASE); // 같은 멱등 키가 이미 있다면 409
        }
        long myContribScore = outcome.code();

        // 구매 이력 저장
        UserItem userItem = UserItem.ofPotion(userId, item, expiredAt);
        UserItem saved;
        try {
            saved = this.potionPurchaseRecorder.record(userItem);
        } catch (RuntimeException e) {
            // Redis 차감·효과는 DB 롤백으로 돌아오지 않으므로 보상 Lua로 되돌린 뒤 원래 예외를 그대로 던진다
            // 보상까지 실패해도 원래 저장 실패 원인을 덮지 않는다. 이때 멱등 키가 남아 같은 키 재시도는 409
            try {
                this.redisPotionPurchaser.compensate(
                        userId, universityId, item.getPrice(), idempotencyKey, outcome.previousEffect());
            } catch (RuntimeException compensateFailure) {
                e.addSuppressed(compensateFailure);
                log.error("물약 구매 보상 실패: userId={}, idempotencyKey={}", userId, idempotencyKey, compensateFailure);
            }
            throw e;
        }

        // 기여도 아이템이라 크레딧은 차감되지 않은 현재 값
        return new ItemPurchaseResult(
                saved.getId(),
                item,
                saved.getPrice(),
                saved.getExpiredAt(),
                myContribScore,
                this.creditWallet.findBalance(userId)
        );
    }
}