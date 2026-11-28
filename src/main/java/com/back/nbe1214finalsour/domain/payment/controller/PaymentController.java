package com.back.nbe1214finalsour.domain.payment.controller;

import com.back.nbe1214finalsour.domain.payment.controller.docs.PaymentApiDocs;
import com.back.nbe1214finalsour.domain.payment.dto.*;
import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.service.PaymentConfirmResult;
import com.back.nbe1214finalsour.domain.payment.service.PaymentService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController implements PaymentApiDocs {

    private final PaymentService paymentService;

    // 크레딧 충전 주문 생성. 회원은 토큰에서 꺼내고, 금액은 허용 금액(ChargeAmount)으로 바꿔 서비스에 넘긴다
    // 허용되지 않은 금액이면 ChargeAmount.from이 400 PAY_INVALID_AMOUNT를 던진다
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentOrderResponse> createOrder(
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody PaymentOrderRequest request
    ) {
        ChargeAmount chargeAmount = ChargeAmount.from(request.amount());
        Payment payment = paymentService.createOrder(user.userId(), chargeAmount);
        return ApiResponse.ok(new PaymentOrderResponse(payment));
    }

    // 결제창 실패 기록. 회원은 토큰에서 꺼내고, failUrl의 code·message를 서비스에 넘긴다
    // 1행 성공과 이미 ABORTED(새로고침) 모두 같은 200 응답이다
    @PostMapping("/fail")
    public ApiResponse<PaymentFailResponse> recordFail(
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody PaymentFailRequest request
    ) {
        paymentService.recordFail(user.userId(), request.orderId(), request.code(), request.message());
        return ApiResponse.ok(new PaymentFailResponse(request.orderId(), PaymentStatus.ABORTED));
    }

    // 결제 승인. successUrl의 세 값을 받아 토스 승인 후 크레딧을 충전한다
    // 토스 결과를 모르면 202(확인 중), 이미 승인된 같은 요청이면 저장된 값으로 200
    // 결과에 따라 상태 코드가 달라서 HttpServletResponse로 202를 직접 지정한다 (지정하지 않으면 200)
    @PostMapping("/confirm")
    public ApiResponse<?> confirm(
            @AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody PaymentConfirmRequest request,
            HttpServletResponse response
    ) {
        PaymentConfirmResult result = paymentService.confirmPayment(
                user.userId(), request.orderId(), request.amount(), request.paymentKey());
        if (result.status() == PaymentStatus.IN_PROGRESS) {
            // 토스 결과를 몰라 확인 중이다. 상태 코드만 202로 바꾸고 본문은 그대로 돌려준다
            response.setStatus(HttpStatus.ACCEPTED.value());
            return ApiResponse.ok(new PaymentPendingResponse(result.orderId()));
        }
        return ApiResponse.ok(new PaymentConfirmResponse(result));
    }
}
