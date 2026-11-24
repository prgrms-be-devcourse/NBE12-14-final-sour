package com.back.nbe1214finalsour.domain.item.repository;

/**
 * 물약 구매 Lua의 결과.
 *
 * @param code           0 이상이면 차감 후 잔액, -1이면 기여도 부족(기록 없음 포함), -2이면 같은 멱등 키로 이미 구매함
 * @param previousEffect 덮어쓰기 전 효과 JSON. 없거나 실패면 "". Java는 열지 않고 보상 Lua에 그대로 넘긴다
 */
public record PotionPurchaseOutcome(long code, String previousEffect) {
}
