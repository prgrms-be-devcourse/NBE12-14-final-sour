package com.back.nbe1214finalsour.domain.payment.repository;

import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * READY인 본인 주문만 ABORTED로 바꾸고 결제창 실패 코드·메시지를 남긴다. updated_at은 트리거가 채운다.
     * 상태 확인과 변경을 UPDATE 한 번으로 하므로, 승인(READY → IN_PROGRESS)과 동시에 와도 한쪽만 1을 받는다.
     * 벌크 UPDATE는 영속성 컨텍스트를 거치지 않으므로 앞뒤로 flush·clear 해서 같은 트랜잭션의 엔티티가 옛 상태로 남지 않게 한다.
     *
     * @return 바뀐 행 수. 0이면 주문이 없거나, 남의 주문이거나, READY가 아니다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET status = 'ABORTED', fail_code = :failCode, fail_message = :failMessage
             WHERE order_id = :orderId
               AND user_id = :userId
               AND status = 'READY'
            """, nativeQuery = true)
    int abortIfReady(@Param("orderId") String orderId, @Param("userId") Long userId,
                     @Param("failCode") String failCode, @Param("failMessage") String failMessage);

    // 실패 기록 UPDATE가 0행일 때 이유를 가리려고 주문을 찾는다 (uk_payment_order_id로 한 행)
    Optional<Payment> findByOrderId(String orderId);

    /**
     * READY인 본인 주문이고 금액이 같을 때만 IN_PROGRESS로 바꾸고 토스 결제 키를 저장한다.
     * 같은 주문에 승인 요청이 동시에 와도 한쪽만 1을 받는다.
     *
     * @return 바뀐 행 수. 0이면 주문이 없거나, 남의 주문이거나, 금액이 다르거나, READY가 아니다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET status = 'IN_PROGRESS', payment_key = :paymentKey
             WHERE order_id = :orderId
               AND user_id = :userId
               AND amount = :amount
               AND status = 'READY'
            """, nativeQuery = true)
    int startIfReady(@Param("orderId") String orderId, @Param("userId") Long userId,
                     @Param("amount") long amount, @Param("paymentKey") String paymentKey);


    /**
     * IN_PROGRESS 주문만 DONE으로 바꾸고 토스의 결제수단·승인 시각을 저장한다.
     * 스케줄러가 같은 주문을 먼저 처리했으면 0이 되어 충전이 두 번 일어나지 않는다.
     *
     * @return 바뀐 행 수. 0이면 IN_PROGRESS가 아니다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE payment
               SET status = 'DONE', method = :method, approved_at = :approvedAt
             WHERE order_id = :orderId
               AND status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int completeIfInProgress(@Param("orderId") String orderId, @Param("method") String method,
                             @Param("approvedAt") OffsetDateTime approvedAt);
}
