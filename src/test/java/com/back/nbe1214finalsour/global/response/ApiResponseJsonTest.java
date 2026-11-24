package com.back.nbe1214finalsour.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 애플리케이션이 실제로 쓰는 Jackson 설정(application.yaml 포함)으로 응답 모양을 고정한다.
 * 기준: API 명세서 §1 공통 규칙·응답 형식.
 */
@JsonTest
class ApiResponseJsonTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("성공 응답은 data가 null이어도 data 필드를 내보낸다")
    void okWithoutData() {
        String json = jsonMapper.writeValueAsString(ApiResponse.ok());

        assertThat(json).isEqualTo("""
                {"code":"SUCCESS","message":"요청이 성공했습니다.","data":null}""");
    }

    @Test
    @DisplayName("성공 응답은 data 안에 결과를 담는다")
    void okWithData() {
        String json = jsonMapper.writeValueAsString(ApiResponse.ok(new Sample(12, "서울대학교")));

        assertThat(json).isEqualTo("""
                {"code":"SUCCESS","message":"요청이 성공했습니다.","data":{"id":12,"name":"서울대학교"}}""");
    }

    @Test
    @DisplayName("실패 응답은 ErrorCode의 코드와 메시지를 쓰고 data는 null이다")
    void failWithoutData() {
        String json = jsonMapper.writeValueAsString(ApiResponse.fail(ErrorCode.COMMON_NOT_FOUND));

        assertThat(json).isEqualTo("""
                {"code":"COMMON_NOT_FOUND","message":"요청한 경로를 찾을 수 없습니다.","data":null}""");
    }

    @Test
    @DisplayName("입력 검증 실패 응답은 data에 field·reason 목록을 담는다")
    void failWithFieldErrors() {
        ApiResponse<List<FieldErrorDetail>> response = ApiResponse.fail(
                ErrorCode.COMMON_INVALID_INPUT,
                List.of(new FieldErrorDetail("nickname", "2~12자여야 합니다.")));

        String json = jsonMapper.writeValueAsString(response);

        assertThat(json).isEqualTo("""
                {"code":"COMMON_INVALID_INPUT","message":"입력값이 올바르지 않습니다.",\
                "data":[{"field":"nickname","reason":"2~12자여야 합니다."}]}""");
    }

    @Test
    @DisplayName("시각은 KST 오프셋(+09:00)을 포함한 ISO-8601 문자열로 내보낸다")
    void offsetDateTimeIsSerializedInKst() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 11, 11, 5, 30, 0, 0, ZoneOffset.UTC);

        String json = jsonMapper.writeValueAsString(new TimePayload(utc));

        assertThat(json).isEqualTo("""
                {"at":"2026-11-11T14:30:00+09:00"}""");
    }

    private record Sample(long id, String name) {
    }

    private record TimePayload(OffsetDateTime at) {
    }
}
