package com.back.nbe1214finalsour.domain.alliance.service;

/**
 * 대결 종료 시각을 기준으로 동결된 단 한 번의 점수 스냅샷.
 * 학교 ID는 해당 대결의 두 연합에 실제로 승인된 두 학교씩이어야 한다.
 * 기여도는 연합 가입 후 누적치가 아닌 대결 기간 동안의 학교별 획득 점수다.
 * 지원 점수 포함 여부/기준 시각은 클릭·집계 담당과 같은 정의여야 한다.
 */
public record AllianceMatchSettlementSnapshot(
        long aScore, long bScore,
        Long aUniversity1Id, Long aUniversity2Id,
        Long bUniversity1Id, Long bUniversity2Id,
        long aUniversity1Contribution, long aUniversity2Contribution,
        long bUniversity1Contribution, long bUniversity2Contribution
) {
}
