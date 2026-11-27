package com.back.nbe1214finalsour.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.back.nbe1214finalsour.domain.credit.repository.CreditHistoryRepository;
import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmTxServiceTest {

    // 값이 서로 달라서 인자 순서가 바뀌면 테스트가 잡는다
    private static final Long USER_ID = 1L;
    private static final String ORDER_ID = "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b";
    private static final String PAYMENT_KEY = "tgen_20261127160000AbCd1";
    private static final long AMOUNT = 5_000L;

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
}
