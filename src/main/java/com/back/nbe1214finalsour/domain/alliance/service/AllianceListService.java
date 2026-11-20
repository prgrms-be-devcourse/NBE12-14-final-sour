package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceListReader;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
// Authorize and read from the same DB snapshot; never use a previous page's authorization.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AllianceListService {
    private final AllianceListReader reader;
    private final AllianceQueryService alliances;
    private final AllianceMatchService matches;
    private final AllianceMatchScoreLedger scores;
    private final Clock clock;

    public Page<Summary> directory(@Nullable Long school, Scope scope, @Nullable AllianceStatus status,
                                   boolean recruiting, @Nullable Long cursor, int size) {
        return reader.directory(school, scope, status, recruiting, OffsetDateTime.now(clock), cursor, size);
    }

    public List<School> schools(long allianceId) {
        alliances.getAlliance(allianceId);
        return reader.schools(allianceId, OffsetDateTime.now(clock));
    }

    public Page<Application> received(long allianceId, @Nullable Long school, @Nullable AllianceUniversityStatus status,
                                      @Nullable Long cursor, int size) {
        requireAccess(allianceId, school, false);
        return reader.applications(allianceId, null, status, cursor, size);
    }

    public Page<Application> sent(@Nullable Long school, @Nullable AllianceUniversityStatus status, @Nullable Long cursor, int size) {
        return reader.applications(null, school, status, cursor, size);
    }

    public Page<Match> matches(long allianceId, @Nullable Long school, @Nullable Direction direction,
                               @Nullable AllianceMatchStatus status, @Nullable Long cursor, int size) {
        requireAccess(allianceId, school, status == AllianceMatchStatus.FINISHED);
        return reader.matches(allianceId, direction, status, cursor, size);
    }

    public AllianceMatchResponse match(long matchId, @Nullable Long school) {
        AllianceMatch match = matches.getMatch(matchId);
        if (match.getDeletedAt() != null) throw new BusinessException(ErrorCode.MATCH_NOT_FOUND);
        boolean historical = match.getStatus() == AllianceMatchStatus.FINISHED;
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!reader.canRead(match.getAllianceAId(), school, now, historical)
                && !reader.canRead(match.getAllianceBId(), school, now, historical)) {
            throw new BusinessException(ErrorCode.ALLY_NOT_MEMBER);
        }
        return new AllianceMatchResponse(match, scores.read(match, now));
    }

    private void requireAccess(long allianceId, @Nullable Long school, boolean historical) {
        alliances.getAlliance(allianceId);
        if (!reader.canRead(allianceId, school, OffsetDateTime.now(clock), historical)) {
            throw new BusinessException(ErrorCode.ALLY_NOT_MEMBER);
        }
    }
}
