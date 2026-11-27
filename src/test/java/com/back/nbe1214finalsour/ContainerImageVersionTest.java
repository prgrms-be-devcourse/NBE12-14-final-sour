package com.back.nbe1214finalsour;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 로컬 실행(compose.yaml)과 통합 테스트(TestcontainersConfiguration)가 같은 고정 버전의 이미지를 쓰는지 확인한다 (이슈 #26).
 * 버전을 올릴 때는 두 곳을 함께 바꾼다.
 */
class ContainerImageVersionTest {

    private static final Path COMPOSE = Path.of("compose.yaml");

    @Test
    @DisplayName("compose.yaml의 이미지가 통합 테스트 컨테이너 이미지와 같다")
    void composeMatchesTestcontainers() throws IOException {
        assertThat(composeImages()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "postgres", TestcontainersConfiguration.POSTGRES_IMAGE,
                "redis", TestcontainersConfiguration.REDIS_IMAGE,
                "floci", TestcontainersConfiguration.FLOCI_IMAGE));
    }

    @Test
    @DisplayName("이미지는 latest가 아닌 버전 태그로 고정한다")
    void imagesArePinned() throws IOException {
        assertThat(composeImages().values()).allSatisfy(image -> assertThat(image)
                .withFailMessage("'%s'는 버전이 고정되지 않았습니다. 'postgres:17'처럼 버전 태그를 붙이세요 (latest 금지).", image)
                .matches("[^:]+:[0-9][^:]*"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> composeImages() throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE)) {
            Map<String, Object> compose = new Yaml().load(reader);
            Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) compose.get("services");
            Map<String, String> images = new LinkedHashMap<>();
            services.forEach((name, service) -> images.put(name, (String) service.get("image")));
            return images;
        }
    }
}
