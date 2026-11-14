package com.back.nbe1214finalsour.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

/** Spring 컨텍스트가 필요 없는 단위 테스트. {@code ./gradlew test}로 돈다. */
class RedisKeyTest {

    @Test
    @DisplayName("패턴에 인자를 채워 키를 만든다")
    void ofFillsPattern() {
        assertThat(RedisKey.AUTH_EMAIL_CODE.of("abc123")).isEqualTo("auth:email:code:abc123");
        assertThat(RedisKey.RANK_DEPT.of(7)).isEqualTo("rank:univ:7:dept");
        assertThat(RedisKey.UNIV_CUSTOM_LOGO.of(7)).isEqualTo("univ:7:custom-logo");
        assertThat(RedisKey.CHAT_UNIV_CHANNEL.of(7)).isEqualTo("chat:univ:7");
    }

    @Test
    @DisplayName("인자가 없는 키는 패턴을 그대로 반환한다")
    void ofReturnsPatternWhenKeyTakesNoArgs() {
        assertThat(RedisKey.RANK_UNIV.of()).isEqualTo("rank:univ");
        assertThat(RedisKey.DIRTY_USER.of()).isEqualTo("dirty:user");
        assertThat(RedisKey.SCORE_USER_ATTACK.of()).isEqualTo("score:user:attack");
        assertThat(RedisKey.CACHE_MAP_UNIVERSITIES.of()).isEqualTo("cache:map:universities");
    }

    @Test
    @DisplayName("인자가 두 개인 키도 순서대로 채운다")
    void ofFillsTwoArgsInOrder() {
        assertThat(RedisKey.CLICKS_UNIV_SECOND.of(7, 1763000000L)).isEqualTo("clicks:univ:7:1763000000");
        assertThat(RedisKey.SUBMIT_DEDUPE.of(42, "s-9")).isEqualTo("submit:42:s-9");
    }

    // --- of()가 막아주는 실수 ---

    @Test
    @DisplayName("인자 개수가 패턴과 다르면 거부한다")
    void ofRejectsWrongArgCount() {
        assertThatThrownBy(RedisKey.RANK_DEPT::of)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RANK_DEPT")
                .hasMessageContaining("인자 1개");

        assertThatThrownBy(() -> RedisKey.RANK_DEPT.of(1, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("받은 개수: 2");

        assertThatThrownBy(() -> RedisKey.RANK_UNIV.of("쓸데없는인자"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인자 0개");
    }

    @Test
    @DisplayName("빈 값이나 null은 거부한다")
    void ofRejectsBlankArg() {
        assertThatThrownBy(() -> RedisKey.USER_STATUS.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빈 값");

        assertThatThrownBy(() -> RedisKey.USER_STATUS.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빈 값");

        assertThatThrownBy(() -> RedisKey.USER_STATUS.of((Object) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("빈 값");
    }

    @Test
    @DisplayName("인자에 ':'가 섞이면 거부한다 (키 네임스페이스 충돌 방지)")
    void ofRejectsColonInArg() {
        assertThatThrownBy(() -> RedisKey.USER_STATUS.of("1:status"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("':'를 쓸 수 없습니다");
    }

    // --- 전 상수 불변식 ---

    @ParameterizedTest
    @EnumSource(RedisKey.class)
    @DisplayName("모든 상수는 패턴의 %s 개수만큼 인자를 받으면 자리를 모두 채운다")
    void everyKeyFormatsWithItsOwnArity(RedisKey key) {
        int argCount = key.pattern().split("%s", -1).length - 1;
        Object[] args = new Object[argCount];
        Arrays.fill(args, "x");

        String result = key.of(args);

        assertThat(result).doesNotContain("%").isNotBlank();
        assertThat(result.chars().filter(c -> c == 'x').count()).isEqualTo(argCount);
    }

    @Test
    @DisplayName("두 상수가 같은 패턴을 쓰지 않는다 (한 키가 두 ValueFormat을 갖는 것을 막는다)")
    void patternsAreUnique() {
        List<String> patterns = Arrays.stream(RedisKey.values()).map(RedisKey::pattern).toList();

        assertThat(patterns).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("키 규칙 스냅샷 — 패턴/자료구조/값 표현을 바꾸면 이 테스트가 깨진다")
    void keyRuleSnapshot() {
        List<String> actual = Arrays.stream(RedisKey.values())
                .map(key -> "%s|%s|%s|%s".formatted(
                        key.name(), key.pattern(), key.structure(), key.valueFormat()))
                .toList();

        assertThat(actual).containsExactly(
                // 인증 (Auth)
                "AUTH_EMAIL_CODE|auth:email:code:%s|HASH|PLAIN",
                "AUTH_EMAIL_COOLDOWN|auth:email:cooldown:%s|STRING|PLAIN",
                "AUTH_EMAIL_DAILY|auth:email:daily:%s:%s|STRING|PLAIN",
                "AUTH_SIGNUP_TOKEN|auth:signup:%s|STRING|PLAIN",
                "AUTH_LOGIN_FAIL|auth:login:fail:%s|STRING|PLAIN",
                "AUTH_LOGIN_LOCK|auth:login:lock:%s|STRING|PLAIN",
                // 회원 (User)
                "USER_STATUS|user:%s:status|STRING|PLAIN",
                "USER_CLICKER|user:%s:clicker|STRING|PLAIN",
                "USER_EFFECT|user:%s:effect|HASH|JSON",
                // 아이템 (Item)
                "ITEM_EFFECT|item:%s|HASH|JSON",
                // 순위 (ZSet)
                "RANK_UNIV|rank:univ|ZSET|PLAIN",
                "RANK_DEPT|rank:univ:%s:dept|ZSET|PLAIN",
                "RANK_USER|rank:univ:%s:user|ZSET|PLAIN",
                "SCORE_USER_ATTACK|score:user:attack|HASH|PLAIN",
                "RANK_USER_ABUSE|rank:user:abuse:%s|ZSET|PLAIN",
                // 변경 추적 (Batch)
                "DIRTY_UNIV|dirty:univ|SET|PLAIN",
                "DIRTY_DEPT|dirty:dept|SET|PLAIN",
                "DIRTY_USER|dirty:user|SET|PLAIN",
                // 클릭 (Click)
                "CLICKS_UNIV_SECOND|clicks:univ:%s:%s|STRING|PLAIN",
                "SUBMIT_DEDUPE|submit:%s:%s|STRING|PLAIN",
                // 요청 한도 (RateLimit)
                "RATELIMIT_ACTION|ratelimit:action:%s|STRING|PLAIN",
                "RATELIMIT_CLICK|ratelimit:click:%s|STRING|PLAIN",
                "RATELIMIT_GUESTBOOK|ratelimit:guestbook:%s|STRING|PLAIN",
                "RATELIMIT_CHAT|ratelimit:chat:%s|STRING|PLAIN",
                "RATELIMIT_SUPPORT|ratelimit:support:%s|STRING|PLAIN",
                // 학교 (University)
                "UNIV_CUSTOM_LOGO|univ:%s:custom-logo|STRING|PLAIN",
                "UNIV_ALLIANCE|univ:%s:alliance|STRING|PLAIN",
                "UNIV_MATCH|univ:%s:match|STRING|PLAIN",
                // 대결·연합 (Match, Alliance)
                "MATCH_SCORE|match:%s:score|HASH|PLAIN",
                "ALLIANCE_SCORE|alliance:%s:score|HASH|PLAIN",
                // 캐시 (Cache)
                "CACHE_UNIV_MEMBER_COUNT|cache:univ:%s:member-count|STRING|PLAIN",
                "CACHE_MAP_UNIVERSITIES|cache:map:universities|STRING|JSON",
                // 채팅 (Chat)
                "CHAT_UNIV_CHANNEL|chat:univ:%s|CHANNEL|JSON",
                "CHAT_ALLIANCE_CHANNEL|chat:alliance:%s|CHANNEL|JSON"
        );
    }
}
