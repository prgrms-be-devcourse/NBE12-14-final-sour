package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.image.ImageStorage;
import com.back.nbe1214finalsour.domain.item.image.ImageType;
import com.back.nbe1214finalsour.domain.item.image.LogoImageValidator;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

// 아이템 구매 진입점: 아이템을 찾고 결제 수단에 맞는 서비스로 위임
// 트랜잭션이 없다: 로고 이미지 업로드를 DB 트랜잭션 밖에서 구매보다 먼저 하기 위해서 (업로드 동안 DB 커넥션을 잡지 않음)
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemPurchaseService {

    private static final String LOGO_KEY_PREFIX = "custom-logo/";

    private final ItemService itemService;
    private final CreditPurchaseService creditPurchaseService;
    private final ContributionPurchaseService contributionPurchaseService;
    private final LogoImageValidator logoImageValidator;
    private final ImageStorage imageStorage;

    // 아이템 구매
    public ItemPurchaseResult purchase(Long userId, Long universityId, Long departmentId, Long itemId, MultipartFile logoImage, UUID idempotencyKey) {

        // 캐시에서 아이템 목록
        List<Item> items = this.itemService.getItems();

        // 목록에 해당 Id의 아이템이 있는 지 확인 후 반환
        Item item = items.stream()
                .filter(itemInService -> itemInService.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ITEM_NOT_FOUND));    // 아이템이 리스트에 없으면 404

        // 크레딧 아이템(로고 변경권)
        if (item.getPayType() == PayType.CREDIT) {
            return purchaseLogo(userId, universityId, departmentId, item, logoImage, idempotencyKey);
        }

        // 기여도 아이템(물약)
        return this.contributionPurchaseService.purchase(userId, universityId, item, idempotencyKey);
    }

    // 검증 → 업로드 → 크레딧 구매(트랜잭션). 구매가 실패하면(커밋 실패 포함) 올린 이미지를 지운다
    private ItemPurchaseResult purchaseLogo(Long userId, Long universityId, Long departmentId, Item item, MultipartFile logoImage, UUID idempotencyKey) {
        ImageType type = this.logoImageValidator.validate(logoImage);

        String key = LOGO_KEY_PREFIX + userId + "/" + UUID.randomUUID() + "." + type.getExtension();
        String logoImageUrl = this.imageStorage.upload(key, logoImage, type);

        try {
            return this.creditPurchaseService.purchase(userId, universityId, departmentId, item, logoImageUrl, idempotencyKey);
        } catch (RuntimeException e) {
            // S3는 DB 롤백에 묶이지 않으므로 직접 되돌린다
            deleteUploadedLogo(key, e);
            throw e;
        }
    }

    // 삭제까지 실패해도 원래 구매 실패 원인(예: 크레딧 부족)을 덮지 않는다. 이 경우 S3에 파일만 남는다
    private void deleteUploadedLogo(String key, RuntimeException purchaseFailure) {
        try {
            this.imageStorage.delete(key);
        } catch (RuntimeException deleteFailure) {
            purchaseFailure.addSuppressed(deleteFailure);
            log.warn("로고 구매 실패 후 업로드한 이미지 삭제 실패: key={}", key, deleteFailure);
        }
    }
}