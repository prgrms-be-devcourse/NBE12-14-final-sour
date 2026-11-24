package com.back.nbe1214finalsour.domain.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SupportStatusTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern STATUS_CHECK = Pattern.compile(
            "ck_customer_support_status\\s+CHECK\\s*\\(\\s*status\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("SupportStatus 값은 DB 제약 ck_customer_support_status가 허용하는 값과 같다")
    void matchesDatabaseCheckConstraint() throws IOException {
        Matcher matcher = STATUS_CHECK.matcher(readMigrations());
        assertThat(matcher.find())
                .withFailMessage("마이그레이션에서 ck_customer_support_status 제약을 찾지 못했습니다. 제약 이름이 바뀌었다면 이 테스트도 고치세요.")
                .isTrue();

        List<String> allowed = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .toList();
        List<String> statuses = Arrays.stream(SupportStatus.values()).map(SupportStatus::name).toList();

        assertThat(statuses)
                .withFailMessage("SupportStatus %s와 ck_customer_support_status %s가 다릅니다. 둘을 함께 고치세요.", statuses, allowed)
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    private static String readMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .map(SupportStatusTest::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션 파일을 읽을 수 없습니다: " + path, e);
        }
    }
}
