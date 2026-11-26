package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.projections.DeptUniv;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.projections.UserUniv;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreFlushService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;

    private static final String FLUSHING = ":flushing";

    public void flush() {
        String newUserDirtyKey = RedisKey.DIRTY_USER.of() + FLUSHING;
        String newUnivDirtyKey = RedisKey.DIRTY_UNIV.of() + FLUSHING;
        String newDeptDirtyKey = RedisKey.DIRTY_DEPT.of() + FLUSHING;
        String newClickerDirtyKey = RedisKey.DIRTY_CLICKER.of() + FLUSHING;
        String newAllianceDirtyKey = RedisKey.DIRTY_ALLIANCE.of() + FLUSHING;
        String newMatchDirtyKey = RedisKey.DIRTY_MATCH.of() + FLUSHING;

        // 개인 점수 저장
        if (detach(RedisKey.DIRTY_USER.of(), newUserDirtyKey)) {
            flushUsers(newUserDirtyKey);
        }

        // 학교 점수 저장
        if (detach(RedisKey.DIRTY_UNIV.of(), newUnivDirtyKey)) {
            flushUnivs(newUnivDirtyKey);
        }

        // 학과 점수 저장
        if (detach(RedisKey.DIRTY_DEPT.of(), newDeptDirtyKey)) {
            flushDepts(newDeptDirtyKey);
        }

        // 클리커 레벨 저장 (실패해도 위의 점수 반영은 이미 끝나 있다)
        if (detach(RedisKey.DIRTY_CLICKER.of(), newClickerDirtyKey)) {
            flushClickerLevels(newClickerDirtyKey);
        }

        // 연합 점수 저장
        if (detach(RedisKey.DIRTY_ALLIANCE.of(), newAllianceDirtyKey)) {
            flushAlliance(newAllianceDirtyKey);
        }

        // 대결 점수 저장
        if (detach(RedisKey.DIRTY_MATCH.of(), newMatchDirtyKey)) {
            flushMatch(newMatchDirtyKey);
        }

        // 기존 dirty 제거
        stringRedisTemplate.delete(List.of(newUserDirtyKey, newUnivDirtyKey, newDeptDirtyKey,
                newClickerDirtyKey, newAllianceDirtyKey, newMatchDirtyKey));
    }

    private boolean detach(String dirtyKey, String flushingKey) {
        boolean hasNew = Boolean.TRUE.equals(stringRedisTemplate.hasKey(dirtyKey));
        boolean hasLeftover = Boolean.TRUE.equals(stringRedisTemplate.hasKey(flushingKey));
        if (hasNew && !hasLeftover) {
            stringRedisTemplate.rename(dirtyKey, flushingKey);
        } else if (hasNew) {
            String incomingKey = dirtyKey + ":incoming";
            stringRedisTemplate.rename(dirtyKey, incomingKey);
            stringRedisTemplate.opsForSet().unionAndStore(flushingKey, incomingKey, flushingKey);
            stringRedisTemplate.delete(incomingKey);
        }
        return hasNew || hasLeftover;
    }

    private void flushMatch(String dirtyKey) {
        List<Long> dirtyMatches = stringRedisTemplate.opsForSet().members(dirtyKey).stream().map(Long::parseLong).toList();

        List<Long> ids = new ArrayList<>();
        List<Long> aScores = new ArrayList<>();
        List<Long> bScores = new ArrayList<>();

        for (Long matchId : dirtyMatches) {
            Map<Object, Object> bySide = stringRedisTemplate.opsForHash().entries(RedisKey.MATCH_SCORE.of(matchId));
            if (bySide.isEmpty()) continue;
            ids.add(matchId);
            aScores.add(Long.parseLong((String) bySide.getOrDefault("A", "0")));
            bScores.add(Long.parseLong((String) bySide.getOrDefault("B", "0")));
        }

        if (ids.isEmpty()) return;

        jdbcTemplate.update("""
                update alliance_match m
                set a_score = x.a, b_score = x.b
                from unnest(?::bigint[], ?::bigint[], ?::bigint[]) as x(id, a, b)
                where m.id = x.id
                    and m.status = 'IN_PROGRESS'
                    and (m.a_score, m.b_score) is distinct from (x.a, x.b)
                """, ids.toArray(Long[]::new), aScores.toArray(Long[]::new), bScores.toArray(Long[]::new));
    }

    private void flushAlliance(String dirtyKey) {
        List<Long> dirtyAlliances = stringRedisTemplate.opsForSet().members(dirtyKey).stream().map(Long::parseLong).toList();

        List<Long> ids = new ArrayList<>();
        List<Long> scores = new ArrayList<>();

        List<Long> memberAllianceIds = new ArrayList<>();
        List<Long> memberUnivIds = new ArrayList<>();
        List<Long> memberScores = new ArrayList<>();

        for (Long allianceId : dirtyAlliances) {
            Map<Object, Object> byUniv = stringRedisTemplate.opsForHash().entries(RedisKey.ALLIANCE_SCORE.of(allianceId));
            if (byUniv.isEmpty()) continue;
            byUniv.forEach((univId, score) -> {
                memberAllianceIds.add(allianceId);
                memberUnivIds.add(Long.parseLong((String) univId));
                memberScores.add(Long.parseLong((String) score));
            });
            ids.add(allianceId);
            scores.add(byUniv.values().stream().mapToLong(v -> Long.parseLong((String) v)).sum());
        }

        if (ids.isEmpty()) return;

        // AllianceUniversity totalScore 값 저장
        jdbcTemplate.update("""
                update alliance_university au
                set total_score = x.score
                from unnest(?::bigint[], ?::bigint[], ?::bigint[]) as x(alliance_id, university_id, score)
                where au.id = (select max(id) from alliance_university sau
                            where sau.alliance_id = x.alliance_id
                                and sau.university_id = x.university_id
                                and sau.status in ('ACCEPTED', 'LEFT')
                                and sau.deleted_at is null)
                    and au.total_score is distinct from x.score
                """, memberAllianceIds.toArray(Long[]::new), memberUnivIds.toArray(Long[]::new), memberScores.toArray(Long[]::new));

        // Alliance totalScore 저장
        jdbcTemplate.update("""
                update alliance a
                set total_score = x.score
                from unnest(?::bigint[], ?::bigint[]) as x(id, score)
                where a.id = x.id
                    and a.total_score is distinct from x.score
                """, ids.toArray(Long[]::new), scores.toArray(Long[]::new));
    }

    private void flushDepts(String dirtyKey) {
        List<Long> dirtyDepts = stringRedisTemplate.opsForSet().members(dirtyKey).stream().map(Long::parseLong).toList();

        Map<Long, List<Long>> deptsByUniv = departmentRepository.findUniversityIdsByIdIn(dirtyDepts).stream()
                .collect(Collectors.groupingBy(DeptUniv::universityId,
                        Collectors.mapping(DeptUniv::departmentId, Collectors.toList()))
                );
        List<Long> ids = new ArrayList<>();
        List<Long> scores = new ArrayList<>();

        deptsByUniv.forEach((univId, deptIds) -> {
            String key = RedisKey.RANK_DEPT.of(univId);
            List<Double> values = stringRedisTemplate.opsForZSet()
                    .score(key, deptIds.stream().map(String::valueOf).toArray());
            for (int i = 0; i < deptIds.size(); i++) {
                Double v = values.get(i);
                if (v == null) continue;
                ids.add(deptIds.get(i));
                scores.add(v.longValue());
            }
        });

        if (ids.isEmpty()) return;

        jdbcTemplate.update("""
                update department as d
                set total_score = x.score
                from unnest(?::bigint[], ?::bigint[]) as x(id, score)
                where d.id = x.id
                    and d.total_score is distinct from x.score
                """, ids.toArray(Long[]::new), scores.toArray(Long[]::new));
    }

    private void flushUnivs(String dirtyKey) {
        Set<String> dirtyUnivs = stringRedisTemplate.opsForSet().members(dirtyKey);

        if (dirtyUnivs == null || dirtyUnivs.isEmpty()) return;

        List<String> univIds = new ArrayList<>(dirtyUnivs);
        List<Double> values = stringRedisTemplate.opsForZSet()
                .score(RedisKey.RANK_UNIV.of(), univIds.toArray());
        // 숨김 학교 점수는 공개 학교 순위가 아닌 RANK_UNIV_HIDDEN에 있다 (SCRUM-238)
        List<Double> hiddenValues = stringRedisTemplate.opsForZSet()
                .score(RedisKey.RANK_UNIV_HIDDEN.of(), univIds.toArray());

        List<Long> ids = new ArrayList<>();
        List<Long> scores = new ArrayList<>();
        for (int i = 0; i < univIds.size(); i++) {
            Double v = values.get(i) != null ? values.get(i) : hiddenValues.get(i);
            if (v == null) continue;
            ids.add(Long.parseLong(univIds.get(i)));
            scores.add(v.longValue());
        }

        jdbcTemplate.update("""
                update university as u
                set total_score = x.score
                from unnest(?::bigint[], ?::bigint[]) as x(id, score)
                where u.id = x.id
                    and u.total_score is distinct from x.score
                """, ids.toArray(Long[]::new), scores.toArray(Long[]::new));
    }

    private void flushClickerLevels(String dirtyKey) {
        List<Long> userIds = stringRedisTemplate.opsForSet().members(dirtyKey).stream().map(Long::parseLong).toList();

        List<String> values = stringRedisTemplate.opsForValue()
                .multiGet(userIds.stream().map(RedisKey.USER_CLICKER::of).toList());

        List<Long> ids = new ArrayList<>();
        List<Long> levels = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            String v = values.get(i);
            if (v == null) {
                skipped.add(userIds.get(i));
                continue;
            }
            ids.add(userIds.get(i));
            levels.add(Long.parseLong(v));
        }

        if (!ids.isEmpty()) {
            jdbcTemplate.update("""
                    update users as u
                    set clicker_id = x.level
                    from unnest(?::bigint[], ?::bigint[]) as x(id, level)
                    where u.id = x.id
                        and u.clicker_id < x.level
                    """, ids.toArray(Long[]::new), levels.toArray(Long[]::new));
        }

        // 레벨 키가 없으면 그 회원의 레벨업이 DB에 반영되지 못한다. 수동 보정용으로 한 번만 남긴다
        if (!skipped.isEmpty()) {
            log.warn("레벨 키가 없어 DB 반영을 건너뜀 userIds={}", skipped);
        }
    }

    private void flushUsers(String dirtyKey) {
        List<Long> dirtyUsers = stringRedisTemplate.opsForSet().members(dirtyKey).stream().map(Long::parseLong).toList();

        Map<Long, List<Long>> usersByUniv = userRepository.findUniversityIdsByIdIn(dirtyUsers).stream()
                .collect(Collectors.groupingBy(
                        UserUniv::universityId,
                        Collectors.mapping(UserUniv::userId, Collectors.toList())
                ));

        List<Long> ids = new ArrayList<>();
        List<Long> scores = new ArrayList<>();
        List<Long> attackScores = new ArrayList<>();
        usersByUniv.forEach((univId, userIds) -> {
            List<String> members = userIds.stream().map(String::valueOf).toList();
            List<Double> contributions = stringRedisTemplate.opsForZSet()
                    .score(RedisKey.RANK_USER.of(univId), members.toArray());
            List<Object> attacks = stringRedisTemplate.opsForHash()
                    .multiGet(RedisKey.SCORE_USER_ATTACK.of(), new ArrayList<>(members));

            for (int i = 0; i < userIds.size(); i++) {
                Double c = contributions.get(i);
                Object a = attacks.get(i);
                if (c == null && a == null) continue;
                ids.add(userIds.get(i));

                scores.add(c != null ? c.longValue() : null);
                attackScores.add(a != null ? Long.parseLong((String) a) : null);
            }
        });

        if (ids.isEmpty()) return;

        jdbcTemplate.update("""
                update users as u 
                set total_contrib_score = coalesce(x.score, u.total_contrib_score),
                    total_attack_score = coalesce(x.attack_score, u.total_attack_score)
                from unnest(?::bigint[], ?::bigint[], ?::bigint[]) AS x(id, score, attack_score)
                where u.id = x.id
                    and (u.total_contrib_score, u.total_attack_score) is distinct from (
                      coalesce(x.score, u.total_contrib_score), coalesce(x.attack_score, u.total_attack_score))
                """, ids.toArray(Long[]::new), scores.toArray(Long[]::new), attackScores.toArray(Long[]::new));
    }
}
