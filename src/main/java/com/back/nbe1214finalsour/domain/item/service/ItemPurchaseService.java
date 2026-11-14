package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.contribution.ContributionWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.event.PotionPurchasedEvent;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemPurchaseService {

    private final ItemService itemService;
    private final ContributionWallet contributionWallet;
    private final UserItemRepository userItemRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;


    // 아이템 구매
    public void purchase(Long userId, Long universityId, Long itemId) {

        // 캐시에서 아이템 목록
        List<Item> items = this.itemService.getItems();

        // 목록에 해당 Id의 아이템이 있는 지 확인 후 반환
        Item item = items.stream()
                .filter(itemInService -> itemInService.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ITEM_NOT_FOUND));    // 아이템이 리스트에 없으면 404

        // 기여도 아이템일 때
        if (item.getPayType() == PayType.CONTRIBUTION) {
            // Redis의 기여도 차감 시도
            this.contributionWallet.tryDeduct(userId, universityId, item.getPrice())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_SCORE)); // 잔여 기여도가 부족하다면 차감없이 400

            // 구매 이력 저장
            UserItem userItem = UserItem.ofPotion(userId, item, OffsetDateTime.now(this.clock));
            this.userItemRepository.save(userItem);

            // Redis user:{userId}:effect에 효과 적용
            this.eventPublisher.publishEvent(
                    new PotionPurchasedEvent(userId, item.getId(), item.getMultiplier(), userItem.getExpiredAt()));
        }
    }

}
