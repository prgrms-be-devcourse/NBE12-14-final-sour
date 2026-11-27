package com.back.nbe1214finalsour.domain.payment.entity;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Locale;

@Getter
@RequiredArgsConstructor
public enum ChargeAmount {
    KRW_2500(2_500),
    KRW_5000(5_000),
    KRW_10000(10_000);

    private final long amount;

    public static ChargeAmount from(long amount) {
        if (amount == 2_500) {
            return KRW_2500;
        }
        if (amount == 5_000) {
            return KRW_5000;
        }
        if (amount == 10_000) {
            return KRW_10000;
        }
        throw new BusinessException(ErrorCode.PAY_INVALID_AMOUNT);
    }

    public String orderName() {
        // 서버 기본 로케일과 무관하게 천 단위 구분자를 쉼표로 고정한다
        return String.format(Locale.ROOT, "크레딧 %,d", amount);
    }
}
