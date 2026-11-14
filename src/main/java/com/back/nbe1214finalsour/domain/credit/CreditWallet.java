package com.back.nbe1214finalsour.domain.credit;

public interface CreditWallet {

    // 회원의 현재 크레딧 잔액 (원본은 DB users.credit_balance)
    long findBalance(Long userId);
}
