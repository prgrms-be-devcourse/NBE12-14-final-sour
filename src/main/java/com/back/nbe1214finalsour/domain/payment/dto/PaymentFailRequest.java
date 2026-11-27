package com.back.nbe1214finalsour.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

// 결제 실패 기록 요청. orderId는 failUrl 쿼리가 아니라 주문 생성 응답에서 받은 값을 보낸다
// (토스는 주문이 생성되지 않고 이탈한 일부 에러에서 failUrl에 orderId를 주지 않는다)
// 길이는 payment 컬럼과 같다: order_id 64, fail_code 100, fail_message 255
// message는 기록용이라 없어도 받는다. 문서가 보장하지 않는 누락이나 프론트 버그로 code와 상태 변경까지 버리지 않기 위해서다
public record PaymentFailRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 100) String code,
        @Nullable @Size(max = 255) String message) {
}
