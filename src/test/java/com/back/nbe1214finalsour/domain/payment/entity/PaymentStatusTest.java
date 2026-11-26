package com.back.nbe1214finalsour.domain.payment.entity;

import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.ABORTED;
import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.CANCELED;
import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.DONE;
import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.EXPIRED;
import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.IN_PROGRESS;
import static com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus.READY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentStatusTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern STATUS_CHECK = Pattern.compile(
            "ck_payment_status\\s+CHECK\\s*\\(\\s*status\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("PaymentStatus 값은 DB 제약 ck_payment_status가 허용하는 값과 같다")
    void matchesDatabaseCheckConstraint() throws IOException {
        // given
        Matcher matcher = STATUS_CHECK.matcher(readMigrations());
        assertThat(matcher.find())
                .withFailMessage("마이그레이션에서 ck_payment_status 제약을 찾지 못했습니다. 제약 이름이 바뀌었다면 이 테스트도 고치세요.")
                .isTrue();
        List<String> allowed = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .toList();

        // when
        List<String> statuses =
                Arrays.stream(PaymentStatus.values()).map(PaymentStatus::name).toList();

        // then
        assertThat(statuses)
                .withFailMessage("PaymentStatus %s와 ck_payment_status %s가 다릅니다. 둘을 함께 고치세요.", statuses, allowed)
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "READY, IN_PROGRESS",
        "READY, ABORTED",
        "READY, EXPIRED",
        "IN_PROGRESS, DONE",
        "IN_PROGRESS, ABORTED",
        "IN_PROGRESS, EXPIRED",
        "DONE, CANCELED"
    })
    @DisplayName("허용된 전이면 canTransitionTo는 true를 돌려준다")
    void allowsDefinedTransitions(PaymentStatus current, PaymentStatus next) {
        // given: current, next (CsvSource)

        // when
        boolean result = current.canTransitionTo(next);

        // then
        assertThat(result).isTrue();
    }

    // 결정한 허용 전이 7쌍. 운영 코드의 switch와 따로 적어, 둘이 어긋나면 테스트가 깨진다
    private static final Set<List<PaymentStatus>> ALLOWED = Set.of(
            List.of(READY, IN_PROGRESS), List.of(READY, ABORTED), List.of(READY, EXPIRED),
            List.of(IN_PROGRESS, DONE), List.of(IN_PROGRESS, ABORTED), List.of(IN_PROGRESS, EXPIRED),
            List.of(DONE, CANCELED));

    static Stream<Arguments> undefinedTransitions() {
        return Arrays.stream(PaymentStatus.values())
                .flatMap(current -> Arrays.stream(PaymentStatus.values())
                        .filter(next -> !ALLOWED.contains(List.of(current, next)))
                        .map(next -> Arguments.of(current, next)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("undefinedTransitions")
    @DisplayName("허용되지 않은 전이면 canTransitionTo는 false를 돌려준다")
    void rejectsUndefinedTransitions(PaymentStatus current, PaymentStatus next) {
        // given: current, next (허용 7쌍을 뺀 나머지 29쌍)

        // when
        boolean result = current.canTransitionTo(next);

        // then
        assertThat(result).isFalse();
    }

    private static String readMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .map(PaymentStatusTest::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("마이그레이션 파일을 읽을 수 없습니다: " + path, e);
        }
    }
}
