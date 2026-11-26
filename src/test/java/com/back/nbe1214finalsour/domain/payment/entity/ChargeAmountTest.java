package com.back.nbe1214finalsour.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChargeAmountTest {

    private Locale originalLocale;

    @BeforeEach
    void saveDefaultLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(originalLocale); // JVM 전역 설정이라 다른 테스트로 새지 않게 되돌린다
    }

    @ParameterizedTest(name = "{0}원 → {1}")
    @CsvSource({
        "2500, KRW_2500",
        "5000, KRW_5000",
        "10000, KRW_10000"
    })
    @DisplayName("허용된 금액이면 from은 그 금액의 ChargeAmount를 돌려준다")
    void returnsChargeAmountForAllowedAmount(long amount, ChargeAmount expected) {
        // given: amount, expected (CsvSource)

        // when
        ChargeAmount result = ChargeAmount.from(amount);

        // then
        assertThat(result).isSameAs(expected);
        assertThat(result.getAmount()).isEqualTo(amount);
    }

    @ParameterizedTest(name = "{0}원")
    @ValueSource(longs = {0, -5_000, 2_499, 2_501, 1_000, 30_000})
    @DisplayName("허용되지 않은 금액이면 from은 PAY_INVALID_AMOUNT 예외를 던진다")
    void throwsInvalidAmountForDisallowedAmount(long amount) {
        // given: amount (ValueSource)

        // when & then
        assertThatThrownBy(() -> ChargeAmount.from(amount))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAY_INVALID_AMOUNT);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "KRW_2500, '크레딧 2,500'",
        "KRW_5000, '크레딧 5,000'",
        "KRW_10000, '크레딧 10,000'"
    })
    @DisplayName("기본 로케일이 독일이어도 orderName은 쉼표 구분자로 '크레딧 금액'을 만든다")
    void formatsOrderNameWithCommaRegardlessOfDefaultLocale(ChargeAmount chargeAmount, String expected) {
        // given: 독일 로케일은 천 단위 구분자가 '.'이다 (5.000)
        Locale.setDefault(Locale.GERMANY);

        // when
        String orderName = chargeAmount.orderName();

        // then
        assertThat(orderName).isEqualTo(expected);
    }
}
