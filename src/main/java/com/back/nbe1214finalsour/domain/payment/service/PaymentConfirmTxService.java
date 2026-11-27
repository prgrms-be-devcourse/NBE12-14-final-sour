package com.back.nbe1214finalsour.domain.payment.service;

import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.repository.PaymentRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentConfirmTxService {

    private final PaymentRepository paymentRepository;
    private final CreditWallet creditWallet;

    // 주문 상태 -> IN_PROGRESS로 변경 + payment_key 저장
    @Transactional
    public PaymentConfirmResult startConfirm(Long userId, String orderId, String paymentKey, long amount){
        int updated = this.paymentRepository.startIfReady(orderId, userId, amount, paymentKey);
        if (updated == 1){
            return new PaymentConfirmResult(orderId);
        }
        return checkWhyNotStarted(userId, orderId, paymentKey, amount);
    }

     // IN_PROGRESS로 상태 변경 실패 시 유형 분석 후 예외 처리
    private PaymentConfirmResult checkWhyNotStarted(Long userId, String orderId, String paymentKey, long amount){
        // 주문이 없으면 404
        Payment payment = this.paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND));

        // 주문이 있어도 남의 주문이면 404
        if (!payment.getUserId().equals(userId)){
            throw new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND);
        }

        // 생성한 주문과 결제 금액이 다를 시 400
        if (payment.getAmount() != amount){
            throw new BusinessException(ErrorCode.PAY_AMOUNT_MISMATCH);
        }

        // 이미 승인됐고 같은 결제 키면 같은 요청이 다시 온 것. 충전하지 않고 저장된 값을 응답
        if (payment.getStatus() == PaymentStatus.DONE && paymentKey.equals(payment.getPaymentKey())){
            long balance = this.creditWallet.findBalance(userId);
            return new PaymentConfirmResult(orderId, PaymentStatus.DONE, payment.getMethod(), payment.getApprovedAt(), payment.getAmount(), balance);
        }

        // 0행인데 본인 READY이고 금액도 같으면 UPDATE 쿼리 자체가 잘못된 것이다. 숨기지 않고 500으로 드러낸다
        if (payment.getStatus() == PaymentStatus.READY) {
            throw new IllegalStateException("READY 주문인데 승인 시작 UPDATE가 0행: orderId=" + orderId);
        }
        // 그 외(IN_PROGRESS·ABORTED·EXPIRED·CANCELED, 다른 결제 키의 DONE)는 이미 처리된 주문이다
        throw new BusinessException(ErrorCode.PAY_ALREADY_PROCESSED);
    }
}
