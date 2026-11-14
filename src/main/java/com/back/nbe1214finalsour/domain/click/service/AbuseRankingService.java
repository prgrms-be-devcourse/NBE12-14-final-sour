package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.click.dto.AbuseRankingResponse;
import com.back.nbe1214finalsour.domain.click.dto.AbuseRankingResponse.AbuseRankingItem;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 관리자용 부정행위 순위. {@link ClickCountLimiter}가 날짜별로 쌓은 {@link RedisKey#RANK_USER_ABUSE}를
 * 최근 {@value #RANK_DAYS}일(오늘 포함) 합산해 횟수가 많은 순으로 보여 준다.
 *
 * <p>부정행위 회원 수는 많지 않으므로 ZUNIONSTORE 임시 키 없이 날짜 키를 모두 읽어 애플리케이션에서 합친다.
 */
@Service
@RequiredArgsConstructor
public class AbuseRankingService {

    private static final int RANK_DAYS = 3;
    // ClickCountLimiter가 순위 키를 만들 때와 같은 기준 (한국 날짜 yyyyMMdd)
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;
    private final UserRepository userRepository;

    public AbuseRankingResponse getRanking(int page, int size) {
        List<Map.Entry<Long, Long>> ranked = sumRecentDays().entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();

        int from = (int) Math.min((long) page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        List<Map.Entry<Long, Long>> slice = ranked.subList(from, to);

        Map<Long, String> nicknames = userRepository.findAllById(slice.stream().map(Map.Entry::getKey).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        List<AbuseRankingItem> content = IntStream.range(0, slice.size())
                .mapToObj(i -> {
                    Map.Entry<Long, Long> entry = slice.get(i);
                    return new AbuseRankingItem(from + i + 1, entry.getKey(),
                            nicknames.get(entry.getKey()), entry.getValue());
                })
                .toList();

        return new AbuseRankingResponse(content, page, size, ranked.size(), to < ranked.size());
    }

    /** userId → 최근 {@value #RANK_DAYS}일 부정행위 횟수 합 */
    private Map<Long, Long> sumRecentDays() {
        LocalDate today = LocalDate.now(ZONE);
        Map<Long, Long> counts = new HashMap<>();
        for (int daysAgo = 0; daysAgo < RANK_DAYS; daysAgo++) {
            String key = RedisKey.RANK_USER_ABUSE.of(today.minusDays(daysAgo).format(DAY));
            var tuples = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            if (tuples == null) {
                continue;
            }
            for (TypedTuple<String> tuple : tuples) {
                counts.merge(Long.valueOf(tuple.getValue()), tuple.getScore().longValue(), Long::sum);
            }
        }
        return counts;
    }
}
