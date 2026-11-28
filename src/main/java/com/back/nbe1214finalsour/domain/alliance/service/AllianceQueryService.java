package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.dto.UniversityAllianceResponse;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

@Service
@RequiredArgsConstructor
public class AllianceQueryService {

    private final AllianceRepository allianceRepository;
    private final AllianceUniversityRepository allianceUniversityRepository;
    private final Clock clock;
    private final UniversityRepository universities;

    @Transactional(readOnly = true)
    public Alliance getAlliance(Long allianceId) {
        return allianceRepository.findById(allianceId)
                .filter(alliance -> alliance.getDeletedAt() == null)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<AllianceUniversity> getAcceptedUniversities(Long allianceId) {

        // 연합이 존재하고 삭제되지 않았는지 확인
        getAlliance(allianceId);

        // 가입 승인된 학교만 조회
        return allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<UniversityAllianceResponse> getUniversityAlliance(long universityId) {
        if (!universities.existsById(universityId)) throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        return findCurrentAlliance(universityId).map(alliance -> new UniversityAllianceResponse(
                alliance.getId(), alliance.getName(), alliance.getStatus(),
                alliance.getStatus() == AllianceStatus.ACTIVE && allianceUniversityRepository
                        .countByAllianceIdAndStatusAndDeletedAtIsNull(alliance.getId(), AllianceUniversityStatus.ACCEPTED)
                        < Alliance.MAX_SCHOOLS));
    }

    /** 내 학교의 현재 연합 (명세 {@code GET /api/v1/alliances/me}). 학교가 없거나 소속 연합이 없으면 비어 있다 */
    @Transactional(readOnly = true)
    public Optional<Alliance> findCurrentAlliance(@Nullable Long universityId) {
        if (universityId == null) {
            return Optional.empty();
        }
        return allianceRepository.findCurrentByUniversityId(universityId, OffsetDateTime.now(clock));
    }
}
