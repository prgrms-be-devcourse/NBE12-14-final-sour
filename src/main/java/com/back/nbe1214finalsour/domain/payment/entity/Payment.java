package com.back.nbe1214finalsour.domain.payment.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
