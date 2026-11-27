package com.back.nbe1214finalsour.domain.payment.controller.docs;

import com.back.nbe1214finalsour.domain.payment.dto.*;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

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

    @Operation(
            summary = "결제 실패 기록",
            description = """
                    결제창에서 인증 실패·취소로 failUrl에 돌아왔을 때 호출한다. READY 주문을 ABORTED로 바꾸고 실패 코드·메시지를 남긴다.
                    결제가 일어나지 않았으므로 크레딧은 바뀌지 않는다.

                    - orderId: failUrl 쿼리가 아니라 **주문 생성 응답으로 받은 값**을 보낸다 (토스는 일부 에러에서 failUrl에 orderId를 주지 않는다)
                    - code·message: failUrl 쿼리의 값을 그대로 보낸다. message는 없어도 된다
                    - 이미 ABORTED인 본인 주문(failUrl 새로고침 등)이면 처음 기록을 유지하고 같은 200을 응답한다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "실패 기록됨 (이미 기록된 경우 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = PaymentErrorDocs.FAIL_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = PaymentErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = PaymentErrorDocs.FAIL_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = PaymentErrorDocs.FAIL_CONFLICT)
    })
    ApiResponse<PaymentFailResponse> recordFail(AuthUser user, PaymentFailRequest request);

    @Operation(
            summary = "결제 승인 (크레딧 충전)",
            description = """
                    토스 결제창에서 인증에 성공해 successUrl로 돌아왔을 때 호출한다. 토스에 승인을 요청하고, 승인되면 크레딧을 충전한다.

                    - paymentKey·orderId·amount: successUrl 쿼리의 값을 그대로 보낸다. amount는 서버에 저장된 주문 금액과 같아야 한다
                    - 200: 승인·충전 완료. 같은 요청을 다시 보내면(성공 페이지 새로고침 등) 충전하지 않고 같은 결과를 응답한다
                    - 202: 토스 응답이 늦거나 결과를 알 수 없어 확인 중이다. 충전 여부는 잠시 후 충전 내역에서 확인하도록 안내한다
                    - 400 PAY_CONFIRM_FAILED: 토스가 거절했다(한도초과 등). data.tossMessage를 사용자에게 보여 준다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "승인·충전 완료 (같은 요청을 다시 보낸 경우 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "확인 중. data는 {orderId, status: IN_PROGRESS}"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = PaymentErrorDocs.CONFIRM_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = PaymentErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = PaymentErrorDocs.CONFIRM_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = PaymentErrorDocs.CONFIRM_CONFLICT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500",
                    description = PaymentErrorDocs.CONFIRM_SERVER_ERROR)
    })
    ResponseEntity<ApiResponse<?>> confirm(AuthUser user, PaymentConfirmRequest request);
}
