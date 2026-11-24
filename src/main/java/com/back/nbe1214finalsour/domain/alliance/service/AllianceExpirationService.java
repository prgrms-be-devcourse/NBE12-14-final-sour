package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AllianceExpirationService {
    private final AllianceRepository allianceRepository;
    private final AllianceUniversityRepository allianceUniversityRepository;
    private final AllianceMatchRepository matchRepository;

    // 진행 중인 대결은 종료 정산을 마친 뒤 다음 스케줄에서 만료한다.
    @Transactional
    public void expireIfDue(Long allianceId) {
        Alliance alliance = allianceRepository.findByIdForUpdate(allianceId).orElse(null);
        if (alliance == null || alliance.getDeletedAt() != null
                || alliance.getStatus() == AllianceStatus.EXPIRED) return;
        OffsetDateTime now = OffsetDateTime.now();
        if (alliance.getExpiredAt() == null || alliance.getExpiredAt().isAfter(now)) return;
        // 이미 처리되지 않은 대결을 가진 연합은 절대로 멤버를 해제하지 않는다.
        if (matchRepository.existsOpenMatchForAlliance(allianceId)) return;
        alliance.expire(now);
        for (AllianceUniversity member : allianceUniversityRepository
                .findByAllianceIdAndDeletedAtIsNullOrderByIdAsc(allianceId)) {
            member.expireMembership();
        }
    }
}
