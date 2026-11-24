package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 두 학교가 지금 같은 연합인지 (SCRUM-187). 연합 지원 허용과 학교 상세의 {@code isMyAlly}가 같은 기준을 쓴다.
 * 소속은 토큰에 없고 승인·탈퇴·만료로 수시로 바뀌므로 매번 DB에서 본다.
 */
@Component
@RequiredArgsConstructor
public class AllianceAllyChecker {

    private final AllianceUniversityRepository allianceUniversityRepository;
    private final Clock clock;

    /** 같은 학교이거나 한쪽이 null이면 false (내 학교는 동맹이 아니다) */
    @Transactional(readOnly = true)
    public boolean areAllies(Long universityId, Long otherUniversityId) {
        if (universityId == null || otherUniversityId == null || universityId.equals(otherUniversityId)) {
            return false;
        }
        return allianceUniversityRepository.existsCurrentAllyPair(universityId, otherUniversityId,
                OffsetDateTime.now(clock), AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED);
    }
}
