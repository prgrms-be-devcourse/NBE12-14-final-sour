package com.back.nbe1214finalsour.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConstraintErrorMappingTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("매핑한 제약 이름은 모두 마이그레이션에 CONSTRAINT 또는 INDEX로 정의돼 있다")
    void constraintNamesExistInMigrations() throws IOException {
        String migrations = readMigrations();

        assertSoftly(softly -> {
            for (String name : ConstraintErrorMapping.constraintNames()) {
                Pattern definition = Pattern.compile("\\b(CONSTRAINT|INDEX)\\s+" + Pattern.quote(name) + "\\b",
                        Pattern.CASE_INSENSITIVE);
                softly.assertThat(definition.matcher(migrations).find())
                        .withFailMessage("ConstraintErrorMapping의 제약 '%s'가 %s의 마이그레이션에 없습니다. "
                                + "오타인지, 제약 이름이 바뀌었는지 확인하고 API 명세서 표와 함께 고치세요.", name, MIGRATION_DIR)
                        .isTrue();
            }
        });
    }

    @Test
    @DisplayName("표에 없는 제약 이름이나 null이면 매핑하지 않는다")
    void unknownConstraint() {
        assertThat(ConstraintErrorMapping.find("uk_university_name")).isEmpty();
        assertThat(ConstraintErrorMapping.find(null)).isEmpty();
        assertThat(ConstraintErrorMapping.find("uk_users_nickname_ci")).contains(ErrorCode.USER_NICKNAME_DUPLICATED);
    }

    private static String readMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.filter(path -> path.toString().endsWith(".sql"))
                    .sorted()
                    .map(ConstraintErrorMappingTest::read)
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
