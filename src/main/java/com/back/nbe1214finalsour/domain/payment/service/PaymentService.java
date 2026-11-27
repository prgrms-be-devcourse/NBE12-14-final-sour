package com.back.nbe1214finalsour.domain.payment.service;

import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
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
public class PaymentService {

    private final PaymentRepository paymentRepository;

    // 충전 주문을 READY로 저장한다. 토스 호출은 없다 (프론트가 응답 값으로 결제창을 연다)
    @Transactional
    public Payment createOrder(Long userId, ChargeAmount chargeAmount) {
        return paymentRepository.save(Payment.ready(userId, chargeAmount));
    }

    // 결제창 실패를 기록한다. READY인 본인 주문만 ABORTED로 바뀐다 (조건은 abortIfReady 쿼리에 있다)
    // @Modifying 쿼리는 트랜잭션이 필요하다. 리포지토리가 열어 주지 않으므로 여기서 연다
    @Transactional
    public void recordFail(Long userId, String orderId, String code, String message) {
        int updated = paymentRepository.abortIfReady(orderId, userId, code, message);
        if (updated == 1) {
            return;
        }
        // 0행: 왜 안 바뀌었는지 조회해서 가린다
        checkWhyNotAborted(userId, orderId);
    }

    // 실패 기록 UPDATE가 0행일 때 이유를 가려 알맞은 예외를 던진다
    private void checkWhyNotAborted(Long userId, String orderId) {
        // 주문이 없으면 404 (람다는 값이 없을 때만 실행되어 예외를 만든다)
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND));
        // 남의 주문도 존재를 숨기고 같은 404. 상태보다 먼저 확인해야 남의 ABORTED 주문에 200을 주지 않는다
        // userId는 Long이라 == 대신 equals로 값을 비교한다
        if (!payment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        // 이미 ABORTED면 앞선 실패 기록이 반영된 것이다 (failUrl 새로고침 등). 멱등하게 성공으로 끝낸다 (결정 1)
        // 첫 기록의 code·message는 UPDATE가 0행이라 그대로 남는다
        if (payment.getStatus() == PaymentStatus.ABORTED) {
            return;
        }
        // 0행인데 본인 READY 주문이면 UPDATE 쿼리 자체가 잘못된 것이다 (예: WHERE의 상태 문자열 오타). 숨기지 않고 500으로 드러낸다
        if (payment.getStatus() == PaymentStatus.READY) {
            throw new IllegalStateException("READY 주문인데 실패 기록 UPDATE가 0행: orderId=" + orderId);
        }
        // 그 외(IN_PROGRESS·DONE·EXPIRED·CANCELED)는 이미 다른 결과가 난 주문이다
        throw new BusinessException(ErrorCode.PAY_ALREADY_PROCESSED);
    }
}
