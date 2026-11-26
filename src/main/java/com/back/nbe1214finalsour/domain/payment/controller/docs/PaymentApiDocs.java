package com.back.nbe1214finalsour.domain.payment.controller.docs;

import com.back.nbe1214finalsour.domain.payment.dto.PaymentOrderRequest;
import com.back.nbe1214finalsour.domain.payment.dto.PaymentOrderResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.payment.controller.PaymentController}의 API 문서. */
@Tag(name = "결제", description = "토스페이먼츠 크레딧 충전 (1원 = 1크레딧)")
public interface PaymentApiDocs {

    @Operation(
            summary = "크레딧 충전 주문 생성",
            description = """
                    충전 주문을 READY 상태로 만든다. 이 단계에서는 결제가 일어나지 않는다.

                    - 허용 금액: 2,500 / 5,000 / 10,000
                    - 응답의 orderId·orderName·amount를 그대로 토스 SDK `requestPayment`에 넘긴다
                    - 같은 금액으로 다시 요청해도 매번 새 주문(새 orderId)이 만들어진다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "주문 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = PaymentErrorDocs.ORDER_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = PaymentErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<PaymentOrderResponse> createOrder(AuthUser user, PaymentOrderRequest request);
}
