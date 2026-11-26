package com.back.nbe1214finalsour.domain.item.controller.docs;

import com.back.nbe1214finalsour.domain.item.dto.ActiveEffectResponse;
import com.back.nbe1214finalsour.domain.item.dto.ItemPurchaseResponse;
import com.back.nbe1214finalsour.domain.item.dto.ItemResponse;
import com.back.nbe1214finalsour.domain.item.dto.PurchaseHistoryResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** {@link com.back.nbe1214finalsour.domain.item.controller.ItemController}의 API 문서. */
@Tag(name = "아이템", description = "상점 아이템 조회와 구매 (물약은 기여도, 로고 변경권은 크레딧)")
public interface ItemApiDocs {

    @Operation(
            summary = "아이템 목록",
            description = "판매 중인 아이템 전체. 로그인 없이 조회할 수 있다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    ApiResponse<List<ItemResponse>> getItems();

    @Operation(
            summary = "적용 중인 물약 효과",
            description = "내게 지금 적용 중인 물약 효과. 효과가 없거나 만료됐으면 data가 null이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ItemErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<ActiveEffectResponse> getActiveEffect(AuthUser user);

    @Operation(
            summary = "내 구매 내역",
            description = "물약과 로고 변경권 구매 내역을 최신순으로 조회한다. page는 0부터, size는 1~100 (기본 20).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ItemErrorDocs.HISTORY_INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ItemErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<PurchaseHistoryResponse> getItemHistory(AuthUser user, @Min(0) int page, @Min(1) @Max(100) int size);

    @Operation(
            summary = "아이템 구매",
            description = """
                    아이템을 산다. 물약은 기여도, 로고 변경권은 크레딧으로 결제한다.

                    - `Idempotency-Key` 헤더 필수 (UUID). 구매 버튼을 누를 때마다 새로 만들고, \
                    같은 구매를 다시 보낼 때(네트워크 재시도 등)는 같은 값을 쓴다
                    - 물약: 본문 없이 요청한다. 정해진 시간 동안 클릭 점수에 배율이 붙고, 이미 적용 중인 효과는 덮어쓴다
                    - 로고 변경권: multipart의 `logoImage` 파트로 이미지(5MB 이하, PNG·JPG·WEBP)를 보낸다. 구매하면 학교·학과·프로필 로고가 바로 바뀐다
                    - 로고 변경권을 같은 키로 두 번 보내면, 남은 크레딧이 가격보다 적을 때는 409가 아니라 400 `ITEM_NOT_ENOUGH_CREDIT`가 먼저 난다. 어느 쪽이든 한 번만 결제된다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "구매 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ItemErrorDocs.PURCHASE_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ItemErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = ItemErrorDocs.ITEM_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = ItemErrorDocs.DUPLICATE_PURCHASE)
    })
    ApiResponse<ItemPurchaseResponse> purchase(AuthUser user, Long itemId, UUID idempotencyKey, MultipartFile logoImage);
}