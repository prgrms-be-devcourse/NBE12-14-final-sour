package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 물약 구매 이력을 자기 트랜잭션으로 저장한다. record가 돌아오면 커밋까지 끝나 있다.
 * 호출하는 쪽(ContributionPurchaseService.purchase)에 @Transactional을 붙이지 말 것:
 * 붙이면 이 메서드가 바깥 트랜잭션에 합류해, 커밋 전에 Redis 보상 여부가 갈리는 경계가 사라진다.
 */
@Component
@RequiredArgsConstructor
public class PotionPurchaseRecorder {

    private final UserItemRepository userItemRepository;

    @Transactional
    public UserItem record(UserItem userItem) {
        return userItemRepository.save(userItem);
    }
}
