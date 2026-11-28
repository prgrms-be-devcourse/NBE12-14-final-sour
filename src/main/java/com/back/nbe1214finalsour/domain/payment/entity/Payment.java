package com.back.nbe1214finalsour.domain.payment.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 토스 orderId. UUID v4 문자열 (ck_payment_order_id: 영문·숫자·-·_·= 6~64자)
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    // 토스 orderName (예: 크레딧 5,000)
    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 결제 금액. 생성 때는 ChargeAmount에서 오지만, 허용 금액이 바뀌어도 과거 주문을 읽을 수 있게 long으로 둔다
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // 토스 결제창 실패 코드·메시지 (실패 기록 때만 채워진다)
    @Column(name = "fail_code", length = 100)
    private String failCode;

    @Column(name = "fail_message", length = 255)
    private String failMessage;

    // 토스 결제 키. 승인 시작(tx1) 때 저장한다. 결제 조회·취소에 쓴다 (uk_payment_payment_key)
    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    // 토스가 돌려준 결제수단 (예: 카드). 승인 완료 때 저장한다
    @Column(length = 20)
    private String method;

    // 토스 승인 시각. 승인 완료 때 저장한다
    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    public static Payment ready(Long userId, ChargeAmount chargeAmount) {
        Payment payment = new Payment();
        payment.orderId = UUID.randomUUID().toString();
        payment.orderName = chargeAmount.orderName();
        payment.userId = userId;
        payment.amount = chargeAmount.getAmount();
        payment.status = PaymentStatus.READY;
        return payment;
    }
}
