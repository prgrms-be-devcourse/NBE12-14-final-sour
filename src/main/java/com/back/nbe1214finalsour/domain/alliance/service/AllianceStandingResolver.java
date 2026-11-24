package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class AllianceStandingResolver {

    private final StringRedisTemplate stringRedisTemplate;
    private final AllianceRepository allianceRepository;
    private final AllianceMatchRepository allianceMatchRepository;

    public AllianceStanding resolve(Long universityId) {
        // 연합 점수, 없으면 DB 조회
        OffsetDateTime now = OffsetDateTime.now();

        String stringAllianceId = stringRedisTemplate.opsForValue().get(RedisKey.UNIV_ALLIANCE.of(universityId));
        Long allianceId = 0L;
        if (stringAllianceId == null) {
            Alliance alliance = allianceRepository.findCurrentByUniversityId(universityId, now)
                    .orElse(null);
            if (alliance != null) {
                stringRedisTemplate.opsForValue().set(RedisKey.UNIV_ALLIANCE.of(universityId),
                        String.valueOf(alliance.getId()),
                        Duration.between(now, alliance.getExpiredAt()));
                allianceId = alliance.getId();
            } else {
                stringRedisTemplate.opsForValue()
                        .set(RedisKey.UNIV_ALLIANCE.of(universityId), "none", Duration.ofSeconds(10));
            }
        } else if (!stringAllianceId.equals("none")) {
            allianceId = Long.parseLong(stringAllianceId);
        }

        // 대결 점수, 없으면 DB 조회
        Long matchId = 0L;
        String team = "";
        if (allianceId != 0L) {
            String matchResult = stringRedisTemplate.opsForValue().get(RedisKey.UNIV_MATCH.of(universityId));
            if (matchResult == null) {
                AllianceMatch match = allianceMatchRepository.findByAllianceId(allianceId, now)
                        .orElse(null);

                if (match != null) {
                    team = match.getAllianceAId().equals(allianceId) ? "A" : "B";
                    stringRedisTemplate.opsForValue()
                            .set(RedisKey.UNIV_MATCH.of(universityId), match.getId() + ":" + team, Duration.between(now, match.getEndedAt()));
                    matchId = match.getId();
                } else {
                    stringRedisTemplate.opsForValue()
                            .set(RedisKey.UNIV_MATCH.of(universityId), "none", Duration.ofSeconds(10));
                }
            } else if (!matchResult.equals("none")) {
                String[] split = matchResult.split(":");
                matchId = Long.parseLong(split[0]);
                team = split[1];
            }
        }

        return new AllianceStanding(allianceId, matchId, team);
    }

    public record AllianceStanding(long allianceId, long matchId, String team) {
        public static final AllianceStanding NONE = new AllianceStanding(0L, 0L, "");
    }
}
