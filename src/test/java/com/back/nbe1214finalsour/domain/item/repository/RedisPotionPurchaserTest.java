package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 물약 구매·보상 Lua를 실제 Redis로 검증한다 (@Transactional 없음: Redis만 쓴다). */
@IntegrationTest
class RedisPotionPurchaserTest {

    private static final long USER_ID = 900_40L;
    private static final long UNIVERSITY_ID = 900_41L;
    private static final long ITEM_ID = 900_42L;
    private static final long OTHER_ITEM_ID = 900_43L;
    private static final long TRIPLE_ITEM_ID = 900_44L;
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_KEY = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P_KEY = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID X_KEY = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final JsonMapper JSON =JsonMapper.builder().build();
    private static final RedisScript<Long> PEXPIRETIME_SCRIPT =
            RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    private static final String RANK_KEY = RedisKey.RANK_USER.of(UNIVERSITY_ID);
    private static final String DIRTY_KEY = RedisKey.DIRTY_USER.of();
    private static final String EFFECT_KEY = RedisKey.USER_EFFECT.of(USER_ID);
    private static final String DEDUPE_KEY = RedisKey.PURCHASE_DEDUPE.of(USER_ID, KEY);
    private static final String VOIDED_KEY = RedisKey.USER_EFFECT_VOIDED.of(USER_ID);

    @Autowired
    private RedisPotionPurchaser purchaser;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UserEffectCache userEffectCache;

    @Autowired
    private RedisHandler redisHandler;

    @AfterEach
    void cleanUp() {
        redis.opsForZSet().remove(RANK_KEY, String.valueOf(USER_ID));
        redis.opsForSet().remove(DIRTY_KEY, String.valueOf(USER_ID));
        redis.delete(EFFECT_KEY);
        redis.delete(DEDUPE_KEY);
        redis.delete(VOIDED_KEY);
    }

    /**
     * 만료 절대 시각(epoch ms). Spring Data Redis에 PEXPIRETIME 전용 API가 없고,
     * connection.execute는 정수 응답을 받지 못해(ByteArrayOutput) Lua로 읽는다.
     */
    private Long pexpireTime(String key) {
        return redis.execute(PEXPIRETIME_SCRIPT, List.of(key));
    }

    private static Map<String, String> effectFields(long itemId, String multiplier, OffsetDateTime expiredAt, UUID purchaseId) {
        return Map.of(
                "itemId", String.valueOf(itemId),
                "multiplier", multiplier,
                "expiredAt", String.valueOf(expiredAt.toInstant().toEpochMilli()),
                "purchaseId", purchaseId.toString());
    }

    private Map<String, String> givenEffect(long itemId, String multiplier, OffsetDateTime expiredAt, UUID purchaseId) {
        Map<String, String> fields = effectFields(itemId, multiplier, expiredAt, purchaseId);
        redis.opsForHash().putAll(EFFECT_KEY, fields);
        redis.expireAt(EFFECT_KEY, expiredAt.toInstant());
        return fields;
    }

    @Test
    @DisplayName("기여도가 충분하면 차감하고 효과와 멱등 키를 남긴다")
    void purchase_success() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 1000);
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        PotionPurchaseOutcome outcome = purchaser.purchase(
                USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(outcome).isEqualTo(new PotionPurchaseOutcome(700, ""));
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(700.0);
        assertThat(redis.opsForSet().isMember(DIRTY_KEY, String.valueOf(USER_ID))).isTrue();
        Map<String, String> effect = redis.<String, String>opsForHash().entries(EFFECT_KEY);
        assertThat(effect).containsExactlyInAnyOrderEntriesOf(Map.of(
                "itemId", String.valueOf(ITEM_ID),
                "multiplier", "2.0",
                "expiredAt", String.valueOf(expiredAt.toInstant().toEpochMilli()),
                "purchaseId", KEY.toString()));
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(expiredAt.toInstant().toEpochMilli());
        assertThat(redis.opsForValue().get(DEDUPE_KEY)).isEqualTo("1");
        assertThat(redis.getExpire(DEDUPE_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 300_000L);
    }

    @Test
    @DisplayName("다른 구매의 효과가 있으면 새 효과로 덮어쓰고 이전 효과를 돌려준다")
    void purchase_returnsPreviousEffect() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 1000);
        OffsetDateTime previousExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> previous = Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0",
                "expiredAt", String.valueOf(previousExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", OTHER_KEY.toString());
        redis.opsForHash().putAll(EFFECT_KEY, previous);
        redis.expireAt(EFFECT_KEY, previousExpiredAt.toInstant());
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        PotionPurchaseOutcome outcome = purchaser.purchase(
                USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(outcome.code()).isEqualTo(700);
        assertThat(outcome.previousEffect()).isNotEmpty();
        Map<String, String> returned = JSON.readValue(outcome.previousEffect(), new TypeReference<Map<String, String>>() {});
        assertThat(returned).containsExactlyInAnyOrderEntriesOf(previous);
        Map<String, String> effect = redis.<String, String>opsForHash().entries(EFFECT_KEY);
        assertThat(effect).containsEntry("itemId", String.valueOf(ITEM_ID))
                .containsEntry("purchaseId", KEY.toString());
        assertThat(redis.getExpire(EFFECT_KEY, TimeUnit.MILLISECONDS)).isGreaterThan(1_800_000L);
    }

    @Test
    @DisplayName("기여도가 부족하면 -1을 돌려주고 아무것도 바꾸지 않는다")
    void purchase_notEnoughScore() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 100);
        OffsetDateTime previousExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> previous = Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0",
                "expiredAt", String.valueOf(previousExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", OTHER_KEY.toString());
        redis.opsForHash().putAll(EFFECT_KEY, previous);
        redis.expireAt(EFFECT_KEY, previousExpiredAt.toInstant());
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        PotionPurchaseOutcome outcome = purchaser.purchase(
                USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(outcome).isEqualTo(new PotionPurchaseOutcome(-1, ""));
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(100.0);
        assertThat(redis.opsForSet().isMember(DIRTY_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(previous);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("기여도 기록이 없으면 -1을 돌려주고 아무것도 만들지 않는다")
    void purchase_noContributionRecord() {
        // given
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        PotionPurchaseOutcome outcome = purchaser.purchase(
                USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(outcome).isEqualTo(new PotionPurchaseOutcome(-1, ""));
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isNull();
        assertThat(redis.opsForSet().isMember(DIRTY_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("구매 Lua가 쓴 효과를 효과 조회와 클릭 엔진 읽기 경로가 읽는다")
    void purchase_effectReadableByReaders() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 1000);
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        purchaser.purchase(USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(userEffectCache.find(USER_ID))
                .contains(new UserEffect(ITEM_ID, 2.0, expiredAt.withOffsetSameInstant(ZoneOffset.UTC)));
        assertThat(redisHandler.getHash(EFFECT_KEY, "multiplier", Double.class)).contains(2.0);
    }

    @Test
    @DisplayName("같은 멱등 키가 이미 있으면 잔액이 모자라도 -1이 아니라 -2를 돌려준다")
    void purchase_duplicateKeyEvenWhenNotEnough() {
        // given: 첫 구매 직후 상태 (기여도 100, 효과 purchaseId=KEY, 멱등 키)
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 100);
        OffsetDateTime firstExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> firstEffect = Map.of(
                "itemId", String.valueOf(ITEM_ID),
                "multiplier", "2.0",
                "expiredAt", String.valueOf(firstExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", KEY.toString());
        redis.opsForHash().putAll(EFFECT_KEY, firstEffect);
        redis.expireAt(EFFECT_KEY, firstExpiredAt.toInstant());
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMillis(60_000L));
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS);

        // when
        PotionPurchaseOutcome outcome = purchaser.purchase(
                USER_ID, UNIVERSITY_ID, ITEM_ID, 300L, 2.0, expiredAt, KEY);

        // then
        assertThat(outcome).isEqualTo(new PotionPurchaseOutcome(-2, ""));
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(100.0);
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(firstEffect);
        assertThat(redis.opsForSet().isMember(DIRTY_KEY, String.valueOf(USER_ID))).isFalse();
        assertThat(redis.opsForValue().get(DEDUPE_KEY)).isEqualTo("1");
        assertThat(redis.getExpire(DEDUPE_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 60_000L);
    }

    @Test
    @DisplayName("보상할 때 효과 주인이고 이전 효과가 없으면 기여도를 되돌리고 효과와 멱등 키를 지운다")
    void compensate_ownerWithoutPrevious() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 700);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        redis.opsForSet().remove(DIRTY_KEY, String.valueOf(USER_ID));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, "");

        // then
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(1000.0);
        assertThat(redis.opsForSet().isMember(DIRTY_KEY, String.valueOf(USER_ID))).isTrue();
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("보상할 때 효과 주인이고 이전 효과가 있으면 이전 효과를 만료까지 그대로 복원한다")
    void compensate_restoresPreviousEffect() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 700);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime previousExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> previous = Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0",
                "expiredAt", String.valueOf(previousExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", OTHER_KEY.toString());

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, JSON.writeValueAsString(previous));

        // then
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(previous);
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(previousExpiredAt.toInstant().toEpochMilli());
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(1000.0);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("보상할 때 이전 효과가 purchaseId 없는 3필드면 취소된 구매의 purchaseId를 남기지 않고 복원한다")
    void compensate_restoresThreeFieldPrevious() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 700);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime previousExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> previous = Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0",
                "expiredAt", String.valueOf(previousExpiredAt.toInstant().toEpochMilli()));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, JSON.writeValueAsString(previous));

        // then
        Map<String, String> effect = redis.<String, String>opsForHash().entries(EFFECT_KEY);
        assertThat(effect).doesNotContainKey("purchaseId");
        assertThat(effect).containsExactlyInAnyOrderEntriesOf(previous);
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(previousExpiredAt.toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("보상할 때 이전 효과에 만료 시각이 없으면 복원하지 않고 효과를 지운다")
    void compensate_previousWithoutExpiredAt() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 700);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        String previous = JSON.writeValueAsString(Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0"));

        // when
        Throwable thrown = catchThrowable(() -> purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, previous));

        // then
        assertThat(thrown).isNull();
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("보상할 때 효과 주인이 다른 구매면 효과를 그대로 두고 기여도만 되돌린다")
    void compensate_notOwner_keepsEffect() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        OffsetDateTime otherExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(45).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> otherEffect = givenEffect(OTHER_ITEM_ID, "4.0", otherExpiredAt, OTHER_KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime tripleExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        String triple = JSON.writeValueAsString(Map.of(
                "itemId", String.valueOf(TRIPLE_ITEM_ID),
                "multiplier", "3.0",
                "expiredAt", String.valueOf(tripleExpiredAt.toInstant().toEpochMilli())));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, triple);

        // then
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(otherEffect);
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(otherExpiredAt.toInstant().toEpochMilli());
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(700.0);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("보상할 때 효과 주인이 다른 구매면 취소된 구매의 이전 효과를 voided에 기록한다")
    void compensate_notOwner_recordsVoided() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        givenEffect(OTHER_ITEM_ID, "4.0", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(45).truncatedTo(ChronoUnit.MILLIS), OTHER_KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime tripleExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.MILLIS);
        String triple = JSON.writeValueAsString(Map.of(
                "itemId", String.valueOf(TRIPLE_ITEM_ID),
                "multiplier", "3.0",
                "expiredAt", String.valueOf(tripleExpiredAt.toInstant().toEpochMilli())));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, triple);

        // then
        assertThat(redis.opsForHash().get(VOIDED_KEY, KEY.toString())).isEqualTo(triple);
        assertThat(redis.getExpire(VOIDED_KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 300_000L);
    }

    @Test
    @DisplayName("보상할 때 이전 효과가 이미 취소된 구매의 것이면 건너뛰고 그 앞의 효과를 복원한다")
    void compensate_skipsVoidedPrevious() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime pExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> pEffect = Map.of(
                "itemId", String.valueOf(OTHER_ITEM_ID),
                "multiplier", "4.0",
                "expiredAt", String.valueOf(pExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", P_KEY.toString());
        redis.opsForHash().put(VOIDED_KEY, X_KEY.toString(), JSON.writeValueAsString(pEffect));
        OffsetDateTime xExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(40).truncatedTo(ChronoUnit.MILLIS);
        String xEffect = JSON.writeValueAsString(Map.of(
                "itemId", String.valueOf(TRIPLE_ITEM_ID),
                "multiplier", "3.0",
                "expiredAt", String.valueOf(xExpiredAt.toInstant().toEpochMilli()),
                "purchaseId", X_KEY.toString()));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, xEffect);

        // then
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(pEffect);
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(pExpiredAt.toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("보상할 때 이전 효과가 연쇄로 여러 번 취소됐으면 살아 있는 효과까지 거슬러 올라가 복원한다")
    void compensate_skipsVoidedChain() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        UUID k1 = UUID.randomUUID();
        UUID k2 = UUID.randomUUID();
        UUID k3 = UUID.randomUUID();
        OffsetDateTime rExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20).truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> rEffect = effectFields(OTHER_ITEM_ID, "4.0", rExpiredAt, P_KEY);
        OffsetDateTime laterExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(40).truncatedTo(ChronoUnit.MILLIS);
        redis.opsForHash().put(VOIDED_KEY, k3.toString(), JSON.writeValueAsString(rEffect));
        redis.opsForHash().put(VOIDED_KEY, k2.toString(), JSON.writeValueAsString(effectFields(TRIPLE_ITEM_ID, "3.0", laterExpiredAt, k3)));
        redis.opsForHash().put(VOIDED_KEY, k1.toString(), JSON.writeValueAsString(effectFields(TRIPLE_ITEM_ID, "3.0", laterExpiredAt, k2)));
        String k1Effect = JSON.writeValueAsString(effectFields(TRIPLE_ITEM_ID, "3.0", laterExpiredAt, k1));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, k1Effect);

        // then
        assertThat(redis.<String, String>opsForHash().entries(EFFECT_KEY)).containsExactlyInAnyOrderEntriesOf(rEffect);
        assertThat(pexpireTime(EFFECT_KEY)).isEqualTo(rExpiredAt.toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("보상할 때 취소 기록의 이전 효과가 빈 값이면 건너뛴 끝에 효과 없음으로 끝난다")
    void compensate_chainEndsWithoutPrevious() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        redis.opsForHash().put(VOIDED_KEY, X_KEY.toString(), "");
        String xEffect = JSON.writeValueAsString(effectFields(
                TRIPLE_ITEM_ID, "3.0", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(40).truncatedTo(ChronoUnit.MILLIS), X_KEY));

        // when
        purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, xEffect);

        // then
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(700.0);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("보상할 때 건너뛸 연쇄가 10번을 넘으면 예외 없이 효과를 지우고 기여도만 되돌린다")
    void compensate_chainOverLimit_deletesEffect() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 400);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        OffsetDateTime laterExpiredAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(40).truncatedTo(ChronoUnit.MILLIS);
        List<UUID> chain = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            chain.add(UUID.randomUUID());
        }
        for (int i = 0; i < 10; i++) {
            redis.opsForHash().put(VOIDED_KEY, chain.get(i).toString(),
                    JSON.writeValueAsString(effectFields(TRIPLE_ITEM_ID, "3.0", laterExpiredAt, chain.get(i + 1))));
        }
        Map<String, String> rEffect = effectFields(OTHER_ITEM_ID, "4.0",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20).truncatedTo(ChronoUnit.MILLIS), P_KEY);
        redis.opsForHash().put(VOIDED_KEY, chain.get(10).toString(), JSON.writeValueAsString(rEffect));
        String firstEffect = JSON.writeValueAsString(effectFields(TRIPLE_ITEM_ID, "3.0", laterExpiredAt, chain.get(0)));

        // when
        Throwable thrown = catchThrowable(() -> purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, firstEffect));

        // then
        assertThat(thrown).isNull();
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(700.0);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }

    @Test
    @DisplayName("보상할 때 이전 효과의 만료 시각이 이미 지났으면 되살리지 않고 효과를 지운다")
    void compensate_previousAlreadyExpired() {
        // given
        redis.opsForZSet().add(RANK_KEY, String.valueOf(USER_ID), 700);
        givenEffect(ITEM_ID, "2.0", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.MILLIS), KEY);
        redis.opsForValue().set(DEDUPE_KEY, "1", Duration.ofMinutes(5));
        String expired = JSON.writeValueAsString(Map.of(
                "itemId", String.valueOf(TRIPLE_ITEM_ID),
                "multiplier", "3.0",
                "expiredAt", "1000",
                "purchaseId", OTHER_KEY.toString()));

        // when
        Throwable thrown = catchThrowable(() -> purchaser.compensate(USER_ID, UNIVERSITY_ID, 300L, KEY, expired));

        // then
        assertThat(thrown).isNull();
        assertThat(redis.hasKey(EFFECT_KEY)).isFalse();
        assertThat(redis.opsForZSet().score(RANK_KEY, String.valueOf(USER_ID))).isEqualTo(1000.0);
        assertThat(redis.hasKey(DEDUPE_KEY)).isFalse();
    }
}
