package com.back.nbe1214finalsour.domain.payment.controller;

import com.back.nbe1214finalsour.domain.payment.controller.docs.PaymentApiDocs;
import com.back.nbe1214finalsour.domain.payment.dto.PaymentFailRequest;
import com.back.nbe1214finalsour.domain.payment.dto.PaymentFailResponse;
import com.back.nbe1214finalsour.domain.payment.dto.PaymentOrderRequest;
import com.back.nbe1214finalsour.domain.payment.dto.PaymentOrderResponse;
import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.service.PaymentService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
}
