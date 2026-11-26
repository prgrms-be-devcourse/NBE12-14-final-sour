package com.back.nbe1214finalsour.domain.alliance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAllianceMatchSettlementSnapshotProviderTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Test
    @DisplayName("최종 확정된 대결 스냅샷의 점수와 학교 기여도를 읽는다")
    void loadFinalSnapshot_returnsCompleteSnapshot() {
        // given
        stubSnapshot(99L, validFields());

        // when
        var snapshot = provider().loadFinalSnapshot(99L);

        // then
        assertThat(snapshot.aScore()).isEqualTo(300L);
        assertThat(snapshot.bScore()).isEqualTo(200L);
        assertThat(snapshot.aUniversity1Id()).isEqualTo(11L);
        assertThat(snapshot.aUniversity2Id()).isEqualTo(12L);
        assertThat(snapshot.bUniversity1Id()).isEqualTo(21L);
        assertThat(snapshot.bUniversity2Id()).isEqualTo(22L);
        assertThat(snapshot.aUniversity1Contribution()).isEqualTo(120L);
        assertThat(snapshot.aUniversity2Contribution()).isEqualTo(180L);
        assertThat(snapshot.bUniversity1Contribution()).isEqualTo(80L);
        assertThat(snapshot.bUniversity2Contribution()).isEqualTo(120L);
    }

    @Test
    @DisplayName("미확정 대결 점수는 보상 지급에 사용하지 않는다")
    void loadFinalSnapshot_rejectsUnfinalizedData() {
        // given
        var fields = validFields();
        fields.remove("finalized");
        stubSnapshot(99L, fields);

        // when & then
        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최종 확정");
    }

    @Test
    @DisplayName("학교별 기여도가 누락되면 정산하지 않는다")
    void loadFinalSnapshot_rejectsMissingContribution() {
        // given
        var fields = validFields();
        fields.remove("bUniversity2Contribution");
        stubSnapshot(99L, fields);

        // when & then
        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bUniversity2Contribution");
    }

    @Test
    @DisplayName("음수 점수는 정산하지 않는다")
    void loadFinalSnapshot_rejectsNegativeScore() {
        // given
        var fields = validFields();
        fields.put("aScore", "-1");
        stubSnapshot(99L, fields);

        // when & then
        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aScore");
    }

    @Test
    @DisplayName("같은 학교를 두 번 포함한 정산 스냅샷은 거부한다")
    void loadFinalSnapshot_rejectsDuplicateUniversity() {
        // given
        var fields = validFields();
        fields.put("bUniversity1Id", "11");
        stubSnapshot(99L, fields);

        // when & then
        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복 학교");
    }

    @Test
    @DisplayName("Redis가 정확하게 표현하지 못하는 큰 정수는 정산하지 않는다")
    void loadFinalSnapshot_rejectsUnsafeInteger() {
        // given
        var fields = validFields();
        fields.put("bUniversity1Contribution", "9007199254740992");
        stubSnapshot(99L, fields);

        // when & then
        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bUniversity1Contribution");
    }


    @Test
    @DisplayName("대결 ID가 null 또는 0 이하이면 Redis에 접근하지 않는다")
    void loadFinalSnapshot_rejectsInvalidMatchIdWithoutRedisAccess() {
        assertThatThrownBy(() -> provider().loadFinalSnapshot(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대결 ID");
        assertThatThrownBy(() -> provider().loadFinalSnapshot(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대결 ID");
        assertThatThrownBy(() -> provider().loadFinalSnapshot(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대결 ID");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis에 정산 스냅샷 자체가 없으면 거부한다")
    void loadFinalSnapshot_rejectsNullHashEntries() {
        stubSnapshot(99L, null);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최종 확정");
    }

    @Test
    @DisplayName("finalized가 0인 미확정 정산 스냅샷은 거부한다")
    void loadFinalSnapshot_rejectsFinalizedZero() {
        var fields = validFields();
        fields.put("finalized", "0");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최종 확정");
    }

    @Test
    @DisplayName("학교 ID가 0이면 양수 조건을 위반하여 거부한다")
    void loadFinalSnapshot_rejectsZeroUniversityId() {
        var fields = validFields();
        fields.put("aUniversity1Id", "0");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aUniversity1Id");
    }

    @Test
    @DisplayName("학교 ID 필드가 누락되면 거부한다")
    void loadFinalSnapshot_rejectsMissingUniversityId() {
        var fields = validFields();
        fields.remove("aUniversity2Id");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aUniversity2Id");
    }

    @Test
    @DisplayName("필드 값이 문자열이 아니라면 숫자로 변환하지 않고 거부한다")
    void loadFinalSnapshot_rejectsNonStringField() {
        var fields = validFields();
        fields.put("bScore", 200L);
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bScore");
    }

    @Test
    @DisplayName("숫자가 아닌 문자열은 정산 스냅샷으로 허용하지 않는다")
    void loadFinalSnapshot_rejectsMalformedNumber() {
        var fields = validFields();
        fields.put("aScore", "not-a-number");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aScore")
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("Long 범위를 넘어선 정수 문자열은 정산하지 않는다")
    void loadFinalSnapshot_rejectsLongOverflow() {
        var fields = validFields();
        fields.put("aScore", "9223372036854775808");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aScore")
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("학교별 기여도가 음수이면 정산하지 않는다")
    void loadFinalSnapshot_rejectsNegativeContribution() {
        var fields = validFields();
        fields.put("aUniversity2Contribution", "-42");
        stubSnapshot(99L, fields);

        assertThatThrownBy(() -> provider().loadFinalSnapshot(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aUniversity2Contribution");
    }

    @Test
    @DisplayName("정확하게 표현할 수 있는 최대 정수는 허용한다")
    void loadFinalSnapshot_acceptsMaxExactRedisInteger() {
        var fields = validFields();
        fields.put("aScore", "9007199254740991");
        fields.put("bUniversity2Contribution", "9007199254740991");
        stubSnapshot(99L, fields);

        var snapshot = provider().loadFinalSnapshot(99L);

        assertThat(snapshot.aScore()).isEqualTo(9_007_199_254_740_991L);
        assertThat(snapshot.bUniversity2Contribution()).isEqualTo(9_007_199_254_740_991L);
    }

    @Test
    @DisplayName("0점 및 0 기여도는 유효한 정산 값으로 허용한다")
    void loadFinalSnapshot_acceptsZeroScoresAndContributions() {
        var fields = validFields();
        fields.put("aScore", "0");
        fields.put("bScore", "0");
        fields.put("aUniversity1Contribution", "0");
        fields.put("bUniversity2Contribution", "0");
        stubSnapshot(99L, fields);

        var snapshot = provider().loadFinalSnapshot(99L);

        assertThat(snapshot.aScore()).isZero();
        assertThat(snapshot.bScore()).isZero();
        assertThat(snapshot.aUniversity1Contribution()).isZero();
        assertThat(snapshot.bUniversity2Contribution()).isZero();
    }

    private RedisAllianceMatchSettlementSnapshotProvider provider() {
        return new RedisAllianceMatchSettlementSnapshotProvider(redisTemplate);
    }

    private void stubSnapshot(Long matchId, Map<Object, Object> fields) {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("match:" + matchId + ":settlement:snapshot"))
                .thenReturn(fields);
    }

    private static Map<Object, Object> validFields() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("finalized", "1");
        fields.put("aScore", "300");
        fields.put("bScore", "200");
        fields.put("aUniversity1Id", "11");
        fields.put("aUniversity2Id", "12");
        fields.put("bUniversity1Id", "21");
        fields.put("bUniversity2Id", "22");
        fields.put("aUniversity1Contribution", "120");
        fields.put("aUniversity2Contribution", "180");
        fields.put("bUniversity1Contribution", "80");
        fields.put("bUniversity2Contribution", "120");
        return fields;
    }
}
