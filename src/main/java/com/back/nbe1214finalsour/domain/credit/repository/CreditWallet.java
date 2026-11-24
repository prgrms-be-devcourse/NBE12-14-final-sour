package com.back.nbe1214finalsour.domain.credit.repository;

import java.util.OptionalLong;

public interface CreditWallet {

    // 회원의 현재 크레딧 잔액 (원본은 DB users.credit_balance)
    long findBalance(Long userId);

    // 잔액이 충분하면 차감하고 차감 후 잔액을 반환, 부족하면 차감하지 않고 empty 반환
    OptionalLong tryDeduct(Long userId, long amount);
}
