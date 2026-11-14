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
    // TODO: 동시에 오는 요청에 대해서 잔액을 넘어서는 결제는 막았으나 실수로 더블 클릭하는 경우는 안막음. 프론트에서 잠시 로딩화면 보여줘서 더블 클릭을 방지하거나 백에서 구매 요청에 쿨타임 걸 예정
    @PostMapping("/{itemId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ItemPurchaseResponse> purchase(@AuthenticationPrincipal AuthUser user,
                                                      @PathVariable Long itemId) {
        ItemPurchaseResult result = this.itemPurchaseService.purchase(user.userId(), user.universityId(), itemId);

        // TODO: 크레딧 구매 구현 전까지 크레딧 아이템은 결과가 null
        return ApiResponse.ok(result == null ? null : ItemPurchaseResponse.from(result));
    }
}
