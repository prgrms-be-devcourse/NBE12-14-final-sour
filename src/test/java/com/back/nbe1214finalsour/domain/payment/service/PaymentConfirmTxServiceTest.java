package com.back.nbe1214finalsour.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;

import com.back.nbe1214finalsour.domain.credit.repository.CreditHistoryRepository;
import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.repository.PaymentRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmTxServiceTest {

    // 값이 서로 달라서 인자 순서가 바뀌면 테스트가 잡는다
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String ORDER_ID = "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b";
    private static final String PAYMENT_KEY = "tgen_20261127160000AbCd1";
    private static final String OTHER_PAYMENT_KEY = "tgen_20261127160000ZzZz9";
    private static final long AMOUNT = 5_000L;
    private static final String METHOD = "카드";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-11-27T16:00:00+09:00");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CreditWallet creditWallet;

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @InjectMocks
    private PaymentConfirmTxService service;

    @Test
    @DisplayName("승인 시작 UPDATE가 1행이면 IN_PROGRESS 결과를 돌려주고 주문을 조회하지 않는다")
    void startConfirmReturnsInProgressWhenUpdated() {
        // given
        given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(1);

        // when
        PaymentConfirmResult result = service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT);

        // then
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(result.creditBalance()).isNull();
        then(paymentRepository).should().startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY);
        then(paymentRepository).shouldHaveNoMoreInteractions();
        then(creditWallet).shouldHaveNoInteractions();
    }

    @Nested
    @DisplayName("승인 시작 UPDATE가 0행일 때")
    class WhenStartNotUpdated {

        @Test
        @DisplayName("주문이 없으면 PAY_ORDER_NOT_FOUND 예외를 던진다")
        void throwsNotFoundWhenOrderMissing() {
            // given
            given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("남의 주문이면 금액이 달라도 PAY_ORDER_NOT_FOUND 예외를 던진다")
        void throwsNotFoundWhenOthersOrderEvenIfAmountDiffers() {
            // given
            // 남의 주문인데 금액도 다르다: 금액 검사가 본인 검사보다 먼저면 400이 나가는 상황을 만든다
            // 올바른 코드는 금액을 읽지 않으므로, 이 목만 사용되지 않은 스텁을 허용(LENIENT)한다
            Payment othersPayment = mock(Payment.class, withSettings().strictness(Strictness.LENIENT));
            given(othersPayment.getUserId()).willReturn(OTHER_USER_ID);
            given(othersPayment.getAmount()).willReturn(10_000L); // 요청 금액(5_000)과 다르다
            given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(othersPayment));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            // PAY_AMOUNT_MISMATCH가 아니라 PAY_ORDER_NOT_FOUND여야 남의 주문 금액을 에러 차이로 알아낼 수 없다
            assertThatThrownBy(() -> service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 주문인데 금액이 다르면 PAY_AMOUNT_MISMATCH 예외를 던진다")
        void throwsAmountMismatchWhenAmountDiffers() {
            // given
            Payment myPayment = mock(Payment.class);
            given(myPayment.getUserId()).willReturn(USER_ID);
            given(myPayment.getAmount()).willReturn(10_000L); // 요청 금액(5_000)과 다르다
            given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(myPayment));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);
            then(creditWallet).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("이미 승인된 주문에 같은 결제 키로 요청하면 충전하지 않고 저장된 결과를 돌려준다")
        void returnsStoredResultWhenAlreadyDoneWithSameKey() {
            // given
            Payment donePayment = mock(Payment.class);
            given(donePayment.getUserId()).willReturn(USER_ID);
            given(donePayment.getAmount()).willReturn(AMOUNT);
            given(donePayment.getStatus()).willReturn(PaymentStatus.DONE);
            given(donePayment.getPaymentKey()).willReturn(PAYMENT_KEY);
            given(donePayment.getMethod()).willReturn(METHOD);
            given(donePayment.getApprovedAt()).willReturn(APPROVED_AT);
            given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(donePayment));
            given(creditWallet.findBalance(USER_ID)).willReturn(12_000L);

            // when
            PaymentConfirmResult result = service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT);

            // then
            assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(result.method()).isEqualTo(METHOD);
            assertThat(result.approvedAt()).isEqualTo(APPROVED_AT);
            assertThat(result.chargedCredit()).isEqualTo(5_000L);
            assertThat(result.creditBalance()).isEqualTo(12_000L);
            then(creditWallet).should(never()).charge(anyLong(), anyLong());
            then(creditHistoryRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("이미 승인된 주문에 다른 결제 키로 요청하면 PAY_ALREADY_PROCESSED 예외를 던진다")
        void throwsAlreadyProcessedWhenDoneWithOtherKey() {
            // given
            Payment donePayment = mock(Payment.class);
            given(donePayment.getUserId()).willReturn(USER_ID);
            given(donePayment.getAmount()).willReturn(AMOUNT);
            given(donePayment.getStatus()).willReturn(PaymentStatus.DONE);
            given(donePayment.getPaymentKey()).willReturn(OTHER_PAYMENT_KEY); // 요청의 PAYMENT_KEY와 다르다
            given(paymentRepository.startIfReady(ORDER_ID, USER_ID, AMOUNT, PAYMENT_KEY)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(donePayment));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> service.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ALREADY_PROCESSED);
            then(creditWallet).shouldHaveNoInteractions();
        }
    }
}
