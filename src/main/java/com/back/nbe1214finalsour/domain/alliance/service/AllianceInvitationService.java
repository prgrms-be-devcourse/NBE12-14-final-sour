package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMembershipOrigin;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AllianceInvitationService {
    private final AllianceRepository alliances;
    private final AllianceUniversityRepository memberships;
    private final UserRepository users;
    private final UniversityRepository universities;
    private final Clock clock;

    @Transactional
    public AllianceUniversity invite(Long userId, Long allianceId, Long universityId) {
        User inviter = activeUser(userId);
        Alliance alliance = lockedAlliance(allianceId);
        activeAlliance(alliance);
        List<AllianceUniversity> members = accepted(allianceId);
        if (members.stream().noneMatch(m -> m.getUniversityId().equals(inviter.getUniversityId()))) {
            throw new BusinessException(ErrorCode.ALLY_NOT_MEMBER);
        }
        if (!universities.existsById(universityId)) throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        if (inviter.getUniversityId().equals(universityId)) throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT);
        if (memberships.existsByUniversityIdAndStatusAndDeletedAtIsNull(universityId, AllianceUniversityStatus.ACCEPTED)) {
            throw new BusinessException(ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED);
        }
        if (memberships.existsByAllianceIdAndUniversityIdAndStatusAndDeletedAtIsNull(allianceId, universityId, AllianceUniversityStatus.PENDING)) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_PENDING);
        }
        if (members.size() >= Alliance.MAX_SCHOOLS) throw new BusinessException(ErrorCode.ALLY_FULL);
        return memberships.save(AllianceUniversity.createInvitation(allianceId, universityId, userId));
    }

    @Transactional
    public AllianceUniversity approve(Long userId, Long invitationId) {
        return decide(userId, invitationId, true);
    }

    @Transactional
    public AllianceUniversity reject(Long userId, Long invitationId) {
        return decide(userId, invitationId, false);
    }

    private AllianceUniversity decide(Long userId, Long invitationId, boolean approve) {
        User recipient = activeUser(userId);
        Long allianceId = memberships.findAllianceIdByApplicationId(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));
        activeAlliance(lockedAlliance(allianceId));
        AllianceUniversity invitation = memberships.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND));
        if (invitation.getOrigin() != AllianceMembershipOrigin.INVITATION || invitation.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_FOUND);
        }
        if (!invitation.getUniversityId().equals(recipient.getUniversityId())) throw new BusinessException(ErrorCode.ALLY_NOT_MEMBER);
        if (invitation.getStatus() != AllianceUniversityStatus.PENDING) throw new BusinessException(ErrorCode.ALLY_APPLICATION_NOT_PENDING);
        if (!approve) {
            invitation.reject();
            return invitation;
        }
        int memberCount = accepted(allianceId).size();
        if (memberCount >= Alliance.MAX_SCHOOLS) throw new BusinessException(ErrorCode.ALLY_FULL);
        if (memberships.existsByUniversityIdAndStatusAndDeletedAtIsNull(invitation.getUniversityId(), AllianceUniversityStatus.ACCEPTED)) {
            throw new BusinessException(ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED);
        }
        invitation.approve(userId);
        if (memberCount + 1 >= Alliance.MAX_SCHOOLS) {
            for (AllianceUniversity pending : memberships.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(allianceId, AllianceUniversityStatus.PENDING)) {
                if (!invitationId.equals(pending.getId()) && pending.getStatus() == AllianceUniversityStatus.PENDING) {
                    pending.reject();
                }
            }
        }
        return invitation;
    }

    private User activeUser(Long id) {
        User user = users.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.ALLY_USER_NOT_ACTIVE);
        return user;
    }

    private Alliance lockedAlliance(Long id) {
        return alliances.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.ALLY_NOT_FOUND));
    }

    private void activeAlliance(Alliance alliance) {
        if (alliance.getDeletedAt() != null || alliance.getStatus() != AllianceStatus.ACTIVE || !alliance.getExpiredAt().isAfter(OffsetDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.ALLY_NOT_ACTIVE);
        }
    }

    private List<AllianceUniversity> accepted(Long id) {
        return memberships.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(id, AllianceUniversityStatus.ACCEPTED);
    }
}
