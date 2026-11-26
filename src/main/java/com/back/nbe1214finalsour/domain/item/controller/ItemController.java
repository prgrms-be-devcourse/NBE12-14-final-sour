package com.back.nbe1214finalsour.domain.item.controller;

import com.back.nbe1214finalsour.domain.item.controller.docs.ItemApiDocs;
import com.back.nbe1214finalsour.domain.item.dto.ActiveEffectResponse;
import com.back.nbe1214finalsour.domain.item.dto.ItemPurchaseResponse;
import com.back.nbe1214finalsour.domain.item.dto.ItemResponse;
import com.back.nbe1214finalsour.domain.item.dto.PurchaseHistoryResponse;
import com.back.nbe1214finalsour.domain.item.service.*;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController implements ItemApiDocs {

    private final ItemService itemService;
    private final ItemPurchaseService itemPurchaseService;
    private final ActiveEffectService activeEffectService;
    private final PurchaseHistoryService purchaseHistoryService;

    @GetMapping
    public ApiResponse<List<ItemResponse>> getItems(){
        List<ItemResponse> items = this.itemService.getItems().stream()
                .map(ItemResponse::new)
                .toList();

        return ApiResponse.ok(items);
    }

    // 내게 적용 중인 아이템 효과. 없거나 만료됐으면 data를 null로 응답한다
    @GetMapping("/me/active")
    public ApiResponse<ActiveEffectResponse> getActiveEffect(@AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(this.activeEffectService.findActive(user.userId())
                .map(ActiveEffectResponse::from)
                .orElse(null));
    }

    // 내 아이템 구매 내역. 최신순, 기본 0페이지 20개
    @GetMapping("/me/history")
    public ApiResponse<PurchaseHistoryResponse> getItemHistory(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PurchaseHistoryResponse.from(
                this.purchaseHistoryService.findHistory(user.userId(), page, size)
        ));
    }

    // 아이템 구매. 회원·학교는 토큰에서 꺼낸다 (학교 ID는 기여도 ZSET 키에 필요)
    // 물약은 Idempotency-Key 헤더로 중복 구매를 막는다 (같은 키는 409 ITEM_DUPLICATE_PURCHASE)
    // 로고도 Idempotency-Key로 중복 구매를 막는다
    @PostMapping("/{itemId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ItemPurchaseResponse> purchase(
            @AuthenticationPrincipal AuthUser user,
            @PathVariable Long itemId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            // 로고 아이템만 multipart의 logoImage 파트로 이미지를 보낸다. 물약은 본문 없이 요청하므로 null
            @RequestPart(name = "logoImage", required = false) MultipartFile logoImage
    ) {

        ItemPurchaseResult result = this.itemPurchaseService.purchase(user.userId(), user.universityId(), user.departmentId(), itemId, logoImage, idempotencyKey);

        return ApiResponse.ok(ItemPurchaseResponse.from(result));
    }
}