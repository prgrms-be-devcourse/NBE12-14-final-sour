package com.back.nbe1214finalsour.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code uni_domain} 시드 마이그레이션의 값이 DB 제약과 도메인 형식을 지키는지 확인한다.
 * 학교 이름이 {@code university}에 있는지는 마이그레이션 안의 검사가 확인한다 (없으면 마이그레이션 실패).
 */
class UniDomainSeedTest {

    private static final Path SEED = Path.of("src/main/resources/db/migration/V4__seed_uni_domains.sql");
    private static final Pattern ROW = Pattern.compile("\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*\\)");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    @Test
    @DisplayName("시드 도메인은 소문자 도메인 형식이고 100자 이하이며 서로 겹치지 않는다 (ck_uni_domain_lowercase, uk_uni_domain_domain)")
    void domainsAreValidAndUnique() throws IOException {
        List<String[]> rows = readRows();
        List<String> domains = rows.stream().map(row -> row[1]).toList();

        assertThat(rows).withFailMessage("%s에서 ('학교 이름', '도메인') 행을 찾지 못했습니다.", SEED).isNotEmpty();
        assertThat(domains).allSatisfy(domain -> assertThat(domain)
                .withFailMessage("'%s'는 소문자 도메인 형식이 아니거나 100자를 넘습니다.", domain)
                .matches(DOMAIN)
                .hasSizeLessThanOrEqualTo(100));
        assertThat(domains).withFailMessage("같은 도메인이 두 번 이상 있습니다: %s", domains).doesNotHaveDuplicates();
    }

    private static List<String[]> readRows() throws IOException {
        Matcher matcher = ROW.matcher(Files.readString(SEED));
        List<String[]> rows = new ArrayList<>();
        while (matcher.find()) {
            rows.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        return rows;
    }
}
