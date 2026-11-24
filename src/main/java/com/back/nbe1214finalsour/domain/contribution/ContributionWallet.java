package com.back.nbe1214finalsour.domain.contribution;

public interface ContributionWallet {

    // 회원의 현재 기여도 (교내 개인 순위 ZSET의 점수, 없으면 0)
    long findBalance(Long userId, Long universityId);
}
