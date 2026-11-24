package com.back.nbe1214finalsour.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

class RoleTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern ROLE_CHECK =
            Pattern.compile("ck_users_role\\s+CHECK\\s*\\(\\s*role\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("Role 값은 DB 제약 ck_users_role이 허용하는 값과 같다")
    void matchesDatabaseCheckConstraint() throws IOException {
        Matcher matcher = ROLE_CHECK.matcher(readMigrations());
        assertThat(matcher.find())
                .withFailMessage("마이그레이션에서 ck_users_role 제약을 찾지 못했습니다. 제약 이름이 바뀌었다면 이 테스트도 고치세요.")
                .isTrue();

        List<String> allowed = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .toList();
        List<String> roles = Arrays.stream(Role.values()).map(Role::name).toList();

        assertThat(roles)
                .withFailMessage("Role %s와 ck_users_role %s가 다릅니다. 둘을 함께 고치세요.", roles, allowed)
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    private static String readMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .map(RoleTest::read)
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
