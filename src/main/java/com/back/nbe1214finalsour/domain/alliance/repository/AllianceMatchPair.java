package com.back.nbe1214finalsour.domain.alliance.repository;

/** 연합 행을 먼저 잠그기 위해 조회하는 두 ID만의 스냅샷 (JPA 엔티티 아님). */
public record AllianceMatchPair(Long allianceAId, Long allianceBId) {
}
