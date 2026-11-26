package com.back.nbe1214finalsour.domain.payment.service;

import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    // 충전 주문을 READY로 저장한다. 토스 호출은 없다 (프론트가 응답 값으로 결제창을 연다)
    @Transactional
    public Payment createOrder(Long userId, ChargeAmount chargeAmount) {
        return paymentRepository.save(Payment.ready(userId, chargeAmount));
    }
}
