package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMembershipOrigin;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.chat.entity.AllianceChatRoom;
import com.back.nbe1214finalsour.domain.chat.repository.AllianceChatRoomRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AllianceService {


    private final AllianceRepository allianceRepository;
    private final AllianceChatRoomRepository allianceChatRoomRepository;
    private final AllianceUniversityRepository allianceUniversityRepository;
    private final UserRepository userRepository;

    // 연합 생성
    @Transactional
    public Alliance createAlliance(Long userId, String name, int durationHours) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ALLY_USER_NOT_ACTIVE);
        }

        Long universityId = user.getUniversityId();

        boolean alreadyJoined = allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                );

        if (alreadyJoined) {
            throw new BusinessException(
                    ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
            );
        }

        Alliance alliance;
        try {
            alliance = Alliance.create(
                    name,
                    durationHours,
                    OffsetDateTime.now()
            );
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT);
        }

        Alliance savedAlliance = allianceRepository.save(alliance);
        allianceChatRoomRepository.save(AllianceChatRoom.create(savedAlliance.getId(), savedAlliance.getName()));

        AllianceUniversity founder = AllianceUniversity.createFounder(
                savedAlliance.getId(),
                universityId,
                userId
        );

        allianceUniversityRepository.save(founder);

        return savedAlliance;
    }

    // 연합 가입 신청
    @Transactional
    public AllianceUniversity applyToAlliance(Long userId, Long allianceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ALLY_USER_NOT_ACTIVE);
        }

        Long universityId = user.getUniversityId();

        Alliance alliance = allianceRepository
                .findByIdForUpdate(allianceId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        validateActiveAlliance(alliance);

        boolean alreadyJoined = allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                );

        if (alreadyJoined) {
            throw new BusinessException(
                    ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
            );
        }

        boolean alreadyPending = allianceUniversityRepository
                .existsByAllianceIdAndUniversityIdAndStatusAndDeletedAtIsNull(
                        allianceId,
                        universityId,
                        AllianceUniversityStatus.PENDING
                );

        if (alreadyPending) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_PENDING);
        }

        long memberCount = allianceUniversityRepository
                .countByAllianceIdAndStatusAndDeletedAtIsNull(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                );

        if (memberCount >= Alliance.MAX_SCHOOLS) {
            throw new BusinessException(ErrorCode.ALLY_FULL);
        }

        AllianceUniversity application =
                AllianceUniversity.createApplication(
                        allianceId,
                        universityId,
                        userId
                );

        return allianceUniversityRepository.save(application);
    }

    // 연합 가입 승인
    @Transactional
    public AllianceUniversity approveApplication(
            Long approverUserId,
            Long applicationId
    ) {
        User approver = userRepository.findById(approverUserId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (approver.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ALLY_USER_NOT_ACTIVE);
        }

        // 1. 연합 ID만 조회
        Long allianceId = allianceUniversityRepository
                .findAllianceIdByApplicationId(applicationId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));

        // 2. 연합 비관적 락 획득
        Alliance alliance = allianceRepository
                .findByIdForUpdate(allianceId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        // 3. 락 획득 후 신청 조회
        AllianceUniversity application = allianceUniversityRepository
                .findById(applicationId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));

        validateActiveAlliance(alliance);
        validatePendingApplication(application);

        // 4. 현재 연합 소속 학교 조회
        List<AllianceUniversity> members = allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                );

        // 5. 승인 권한 검증
        validateMembership(approver, members);

        // 6. 연합 정원 검증
        if (members.size() >= Alliance.MAX_SCHOOLS) {
            throw new BusinessException(ErrorCode.ALLY_FULL);
        }

        // 7. 다른 연합에 이미 가입했는지 확인
        boolean alreadyJoined = allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        application.getUniversityId(),
                        AllianceUniversityStatus.ACCEPTED
                );

        if (alreadyJoined) {
            throw new BusinessException(
                    ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
            );
        }

        // 8. 가입 승인
        application.approve(approverUserId);

        // 9. 정원이 가득 차면 나머지 대기 신청 자동 거절
        if (members.size() + 1 >= Alliance.MAX_SCHOOLS) {
            List<AllianceUniversity> pendingApplications =
                    allianceUniversityRepository
                            .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                                    allianceId,
                                    AllianceUniversityStatus.PENDING
                            );

            for (AllianceUniversity pending : pendingApplications) {
                if (pending != application
                        && pending.getStatus() == AllianceUniversityStatus.PENDING) {
                    pending.reject();
                }
            }
        }

        return application;
    }

    // 연합 가입 거절
    @Transactional
    public AllianceUniversity rejectApplication(
            Long rejecterUserId,
            Long applicationId
    ) {
        User rejecter = userRepository.findById(rejecterUserId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (rejecter.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ALLY_USER_NOT_ACTIVE);
        }

        // 1. 연합 ID만 조회
        Long allianceId = allianceUniversityRepository
                .findAllianceIdByApplicationId(applicationId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));

        // 2. 연합 비관적 락 획득
        Alliance alliance = allianceRepository
                .findByIdForUpdate(allianceId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        // 3. 락 획득 후 신청 조회
        AllianceUniversity application = allianceUniversityRepository
                .findById(applicationId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));

        validateActiveAlliance(alliance);
        validatePendingApplication(application);

        // 4. 연합 소속 학교 조회
        List<AllianceUniversity> members = allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                );

        // 5. 거절 권한 검증
        validateMembership(rejecter, members);

        // 6. 가입 거절
        application.reject();

        return application;
    }

    // 연합 활성 상태 검증
    private void validateActiveAlliance(Alliance alliance) {
        if (alliance.getStatus() != AllianceStatus.ACTIVE
                || alliance.getDeletedAt() != null
                || !alliance.getExpiredAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.ALLY_NOT_ACTIVE);
        }
    }

    // 승인 대기 상태 검증
    private void validatePendingApplication(AllianceUniversity application) {
        if (application.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_PENDING);
        }
        if (application.getOrigin() != AllianceMembershipOrigin.APPLICATION) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND);
        }
        if (application.getStatus() != AllianceUniversityStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_PENDING);
        }
    }

    // 연합 소속 학교 회원인지 검증
    private void validateMembership(
            User user,
            List<AllianceUniversity> members
    ) {
        boolean isMember = members.stream()
                .anyMatch(member ->
                        member.getUniversityId().equals(user.getUniversityId()));

        if (!isMember) {
            throw new BusinessException(ErrorCode.ALLY_NOT_MEMBER);
        }
    }
}