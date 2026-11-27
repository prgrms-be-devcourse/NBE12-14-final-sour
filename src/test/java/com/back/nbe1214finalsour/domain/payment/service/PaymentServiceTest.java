package com.back.nbe1214finalsour.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;

import com.back.nbe1214finalsour.domain.payment.client.TossConfirmResult;
import com.back.nbe1214finalsour.domain.payment.client.TossPaymentsClient;
import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String ORDER_ID = "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b";
    private static final String CODE = "PAY_PROCESS_CANCELED";
    private static final String MESSAGE = "사용자에 의해 결제가 취소되었습니다.";
    private static final String PAYMENT_KEY = "tgen_20261127160000AbCd1";
    private static final long AMOUNT = 5_000L;
    private static final String METHOD = "카드";
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-11-27T16:00:00+09:00");

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentConfirmTxService paymentConfirmTxService;

    @Mock
    private TossPaymentsClient tossPaymentsClient;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("주문을 만들면 그 회원·금액의 주문을 저장하고 저장 결과를 돌려준다")
    void createOrderSavesReadyPayment() {
        // given
        Payment stored = Payment.ready(1L, ChargeAmount.KRW_5000); // 리포지토리가 돌려줄 객체
        given(paymentRepository.save(any(Payment.class))).willReturn(stored);

        // when
        Payment result = paymentService.createOrder(1L, ChargeAmount.KRW_5000);

        // then
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getAmount()).isEqualTo(5_000L);
        assertThat(result).isSameAs(stored);
    }

    @Test
    @DisplayName("실패 기록 UPDATE가 1행이면 주문을 조회하지 않고 끝난다")
    void recordFailEndsWithoutLookupWhenUpdated() {
        // given
        given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(1);

        // when
        paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE);

        // then
        then(paymentRepository).should().abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE);
        then(paymentRepository).shouldHaveNoMoreInteractions();
    }

    @Nested
    @DisplayName("실패 기록 UPDATE가 0행일 때")
    class WhenNotUpdated {

        @Test
        @DisplayName("주문이 없으면 PAY_ORDER_NOT_FOUND 예외를 던진다")
        void throwsNotFoundWhenOrderMissing() {
            // given
            given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(0);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ORDER_NOT_FOUND);
        }

        @Test
        @DisplayName("남의 주문이면 이미 ABORTED여도 PAY_ORDER_NOT_FOUND 예외를 던진다")
        void throwsNotFoundWhenOthersOrder() {
            // given
            given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(0);
            // 남의 주문인데 이미 ABORTED: 본인 확인보다 ABORTED 확인이 먼저면 200이 나가는 상황을 만든다
            // 올바른 코드는 상태를 읽지 않으므로, 이 목만 사용되지 않은 스텁을 허용(LENIENT)한다
            Payment othersAborted = mock(Payment.class, withSettings().strictness(Strictness.LENIENT));
            given(othersAborted.getUserId()).willReturn(OTHER_USER_ID);
            given(othersAborted.getStatus()).willReturn(PaymentStatus.ABORTED);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(othersAborted));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ORDER_NOT_FOUND);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = PaymentStatus.class,
                names = {"IN_PROGRESS", "DONE", "EXPIRED", "CANCELED"})
        @DisplayName("본인 주문이 READY도 ABORTED도 아니면 PAY_ALREADY_PROCESSED 예외를 던진다")
        void throwsAlreadyProcessedWhenNotReady(PaymentStatus status) {
            // given
            given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(0);
            Payment payment = mock(Payment.class);
            given(payment.getUserId()).willReturn(USER_ID);
            given(payment.getStatus()).willReturn(status);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다. extracting("errorCode")는 예외의 errorCode 필드를 이름으로 꺼낸다
            assertThatThrownBy(() -> paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PAY_ALREADY_PROCESSED);
        }

        @Test
        @DisplayName("본인 주문이 이미 ABORTED면 예외 없이 끝나고 기록을 다시 쓰지 않는다")
        void endsNormallyWhenAlreadyAborted() {
            // given
            given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(0);
            Payment payment = mock(Payment.class);
            given(payment.getUserId()).willReturn(USER_ID);
            given(payment.getStatus()).willReturn(PaymentStatus.ABORTED);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

            // when
            paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE);

            // then
            then(paymentRepository).should().abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE);
            then(paymentRepository).should().findByOrderId(ORDER_ID);
            then(paymentRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("본인 주문이 READY인데 UPDATE가 0행이면 IllegalStateException을 던진다")
        void throwsIllegalStateWhenStillReady() {
            // given
            given(paymentRepository.abortIfReady(ORDER_ID, USER_ID, CODE, MESSAGE)).willReturn(0);
            // 실제 DB에서는 쿼리를 일부러 틀리지 않는 한 재현할 수 없어 목으로 만든다
            Payment payment = mock(Payment.class);
            given(payment.getUserId()).willReturn(USER_ID);
            given(payment.getStatus()).willReturn(PaymentStatus.READY);
            given(paymentRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

            // when & then
            // 람다 안의 호출이 던진 예외를 검사한다
            assertThatThrownBy(() -> paymentService.recordFail(USER_ID, ORDER_ID, CODE, MESSAGE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ORDER_ID);
        }
    }

    @Nested
    @DisplayName("결제 승인을 요청할 때")
    class WhenConfirming {

        @Test
        @DisplayName("승인 시작이 이미 DONE인 결과를 돌려주면 토스를 부르지 않고 그 결과를 그대로 돌려준다")
        void returnsStoredResultWithoutCallingToss() {
            // given
            PaymentConfirmResult stored =
                    new PaymentConfirmResult(ORDER_ID, PaymentStatus.DONE, METHOD, APPROVED_AT, 5_000L, 12_000L);
            given(paymentConfirmTxService.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT)).willReturn(stored);

            // when
            PaymentConfirmResult result = paymentService.confirmPayment(USER_ID, ORDER_ID, AMOUNT, PAYMENT_KEY);

            // then
            assertThat(result).isSameAs(stored);
            then(tossPaymentsClient).shouldHaveNoInteractions();
            then(paymentConfirmTxService).should(never()).completeConfirm(any(), any(), any());
        }

        @Test
        @DisplayName("토스가 승인하면 승인 완료 결과를 돌려준다")
        void completesWhenTossApproved() {
            // given
            given(paymentConfirmTxService.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .willReturn(new PaymentConfirmResult(ORDER_ID));
            given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(new TossConfirmResult(TossConfirmResult.Type.APPROVED, METHOD, APPROVED_AT, null, null));
            PaymentConfirmResult done =
                    new PaymentConfirmResult(ORDER_ID, PaymentStatus.DONE, METHOD, APPROVED_AT, 5_000L, 12_000L);
            given(paymentConfirmTxService.completeConfirm(ORDER_ID, METHOD, APPROVED_AT)).willReturn(done);

            // when
            PaymentConfirmResult result = paymentService.confirmPayment(USER_ID, ORDER_ID, AMOUNT, PAYMENT_KEY);

            // then
            assertThat(result).isSameAs(done);
            then(paymentConfirmTxService).should(never()).abortConfirm(any(), any(), any());
        }

        @Test
        @DisplayName("토스 결과를 모르면 충전도 거절 기록도 하지 않고 IN_PROGRESS 결과를 돌려준다")
        void returnsInProgressWhenTossOutcomeUnknown() {
            // given
            given(paymentConfirmTxService.startConfirm(USER_ID, ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .willReturn(new PaymentConfirmResult(ORDER_ID));
            given(tossPaymentsClient.confirm(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(new TossConfirmResult(TossConfirmResult.Type.UNKNOWN, null, null, null, "HTTP 500"));

            // when
            PaymentConfirmResult result = paymentService.confirmPayment(USER_ID, ORDER_ID, AMOUNT, PAYMENT_KEY);

            // then
            assertThat(result.orderId()).isEqualTo(ORDER_ID);
            assertThat(result.status()).isEqualTo(PaymentStatus.IN_PROGRESS);
            then(paymentConfirmTxService).should(never()).completeConfirm(any(), any(), any());
            then(paymentConfirmTxService).should(never()).abortConfirm(any(), any(), any());
        }
    }
}
