package com.back.nbe1214finalsour.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    // DB 제약 ck_payment_order_id와 같은 규칙
    private static final String ORDER_ID_RULE = "^[A-Za-z0-9_=-]{6,64}$";
    // UUID v4 정식 문자열: 소문자 16진수, 버전 4, variant 8·9·a·b
    private static final String UUID_V4 = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    @Test
    @DisplayName("ChargeAmount로 주문을 만들면 READY 상태이고 회원·금액·주문명·orderId가 채워진다")
    void readyCreatesReadyOrderFromChargeAmount() {
        // given
        Long userId = 1L;
        ChargeAmount chargeAmount = ChargeAmount.KRW_5000;

        // when
        Payment payment = Payment.ready(userId, chargeAmount);

        // then
        assertThat(payment.getUserId()).isEqualTo(1L);
        assertThat(payment.getAmount()).isEqualTo(5_000L);
        assertThat(payment.getOrderName()).isEqualTo("크레딧 5,000");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getOrderId()).matches(UUID_V4);
        assertThat(payment.getOrderId()).matches(ORDER_ID_RULE);
    }

    @Test
    @DisplayName("같은 회원·금액으로 두 번 만들면 orderId가 서로 다르다")
    void readyGeneratesDifferentOrderIdEachTime() {
        // given
        Long userId = 1L;
        ChargeAmount chargeAmount = ChargeAmount.KRW_5000;

        // when
        Payment first = Payment.ready(userId, chargeAmount);
        Payment second = Payment.ready(userId, chargeAmount);

        // then
        assertThat(first.getOrderId()).isNotEqualTo(second.getOrderId());
    }
}
