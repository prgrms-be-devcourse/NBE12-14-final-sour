package com.back.nbe1214finalsour.domain.item.controller;

import com.back.nbe1214finalsour.domain.item.dto.ItemPurchaseResponse;
import com.back.nbe1214finalsour.domain.item.dto.ItemResponse;
import com.back.nbe1214finalsour.domain.item.service.ItemPurchaseResult;
import com.back.nbe1214finalsour.domain.item.service.ItemPurchaseService;
import com.back.nbe1214finalsour.domain.item.service.ItemService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;
    private final ItemPurchaseService itemPurchaseService;

    @GetMapping
    public ApiResponse<List<ItemResponse>> getItems(){
        List<ItemResponse> items = this.itemService.getItems().stream()
                .map(ItemResponse::new)
                .toList();

        return ApiResponse.ok(items);
    }


    // 아이템 구매. 회원·학교는 토큰에서 꺼낸다 (학교 ID는 기여도 ZSET 키에 필요)
    @PostMapping("/{itemId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ItemPurchaseResponse> purchase(@AuthenticationPrincipal AuthUser user,
                                                      @PathVariable Long itemId) {
        ItemPurchaseResult result = this.itemPurchaseService.purchase(user.userId(), user.universityId(), itemId);

        // TODO: 크레딧 구매 구현 전까지 크레딧 아이템은 결과가 null
        return ApiResponse.ok(result == null ? null : ItemPurchaseResponse.from(result));
    }
}
