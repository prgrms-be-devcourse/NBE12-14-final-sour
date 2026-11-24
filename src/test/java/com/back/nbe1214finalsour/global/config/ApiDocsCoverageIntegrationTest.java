package com.back.nbe1214finalsour.global.config;

import com.back.nbe1214finalsour.IntegrationTest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대상 경로의 API 문서가 요약·오류 응답·인증 정책을 갖는지 확인한다.
 * 다음 단계에서는 PREFIXES와 PUBLIC_OPERATIONS에 담당 경로와 공개 API를 추가한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ApiDocsCoverageIntegrationTest {
    private static final Set<String> PREFIXES = Set.of(
            "/api/v1/auth/", "/api/v1/users/", "/api/v1/admin/users/",
            "/api/v1/supports/", "/api/v1/admin/supports/",
            "/api/v1/chat/", "/api/v1/forbidden-words/", "/api/v1/abuse/");
    // SecurityConfig의 목록은 다른 패키지에서 접근할 수 없다.
    // 인증 선택인 post /api/v1/supports는 넣지 않는다. bearerAuth를 상속해야 Swagger UI가 회원 토큰도 보낸다.
    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "post /api/v1/auth/email/code", "post /api/v1/auth/email/verify",
            "post /api/v1/auth/signup", "post /api/v1/auth/login", "post /api/v1/auth/reissue",
            "get /api/v1/users/nickname/availability");
    private static final Set<String> METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("대상 API 문서에 요약과 오류 응답, 올바른 인증 정책이 있다")
    void documentedOperationsHaveSummaryErrorsAndCorrectSecurity() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode paths = JsonMapper.builder().build().readTree(json).path("paths");
        int count = 0;
        Set<String> foundPublicOperations = new HashSet<>();
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(paths.has("/error")).as("/error는 API 문서에 없어야 한다").isFalse();
        Iterator<Map.Entry<String, JsonNode>> pathFields = paths.properties().iterator();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> path = pathFields.next();
            if (!matchesPrefix(path.getKey())) {
                continue;
            }
            for (Map.Entry<String, JsonNode> entry : path.getValue().properties()) {
                if (!METHODS.contains(entry.getKey())) {
                    continue;
                }
                count++;
                String operation = entry.getKey() + " " + path.getKey();
                JsonNode value = entry.getValue();
                softly.assertThat(value.path("summary").asString("").trim())
                        .as("%s: summary 누락", operation).isNotEmpty();
                boolean hasError = false;
                for (Map.Entry<String, JsonNode> response : value.path("responses").properties()) {
                    if (!response.getKey().matches("2[0-9][0-9]")) {
                        hasError = true;
                    }
                }
                softly.assertThat(hasError).as("%s: 2xx 외 응답 누락", operation).isTrue();
                JsonNode security = value.get("security");
                if (PUBLIC_OPERATIONS.contains(operation)) {
                    foundPublicOperations.add(operation);
                    softly.assertThat(security != null && security.isArray() && security.isEmpty())
                            .as("%s: 공개 API security가 빈 배열이 아님", operation).isTrue();
                } else if (security != null) {
                    boolean bearer = false;
                    for (JsonNode requirement : security) {
                        bearer |= requirement.has("bearerAuth");
                    }
                    softly.assertThat(security.isArray() && bearer)
                            .as("%s: bearerAuth 누락", operation).isTrue();
                }
            }
        }
        softly.assertThat(count).as("대상 prefix에 operation이 없음: %s", PREFIXES).isPositive();
        for (String operation : PUBLIC_OPERATIONS) {
            softly.assertThat(foundPublicOperations).as("%s: 공개 API가 문서에 없음", operation).contains(operation);
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("객체 참조 필드에 중복 example을 지정하지 않는다")
    void referencedPropertiesDoNotDeclareExamples() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode schemas = JsonMapper.builder().build().readTree(json).path("components").path("schemas");
        SoftAssertions softly = new SoftAssertions();
        for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
            for (Map.Entry<String, JsonNode> property : schema.getValue().path("properties").properties()) {
                if (referencesSchema(property.getValue())) {
                    softly.assertThat(property.getValue().has("example"))
                            .as("%s.%s: 객체 필드에는 example을 쓰지 않는다. 하위 스키마의 필드 example을 쓴다",
                                    schema.getKey(), property.getKey())
                            .isFalse();
                }
            }
        }
        softly.assertAll();
    }

    private static boolean referencesSchema(JsonNode schema) {
        if (schema.has("$ref")) {
            return true;
        }
        for (String keyword : Set.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode member : schema.path(keyword)) {
                if (referencesSchema(member)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesPrefix(String path) {
        return PREFIXES.stream().anyMatch(prefix -> path.startsWith(prefix)
                || path.equals(prefix.substring(0, prefix.length() - 1)));
    }
}
