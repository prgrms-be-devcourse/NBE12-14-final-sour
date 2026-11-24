package com.back.nbe1214finalsour.domain.alliance.service;

/**
 * 점수 집계 담당 팀이 제공해야 하는 연결 계약.
 * matchId의 endedAt 시점까지 확정된 A/B 총점 및 학교별 대결 기간 기여도를 반환한다.
 * 임의의 alliance.totalScore나 누적 alliance:{id}:score를 대결 기간 점수로 대체하지 말 것.
 * 이 인터페이스에는 기본 구현 빈이 없으므로 자동 정산은 기본 비활성화된다.
 */
public interface AllianceMatchSettlementSnapshotProvider {
    AllianceMatchSettlementSnapshot loadFinalSnapshot(Long matchId);
}
