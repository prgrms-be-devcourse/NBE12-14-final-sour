package com.back.nbe1214finalsour.global.exception;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ErrorCode} 작성 규칙을 검사한다 (API 명세서 §1 공통 에러 코드).
 * 상수 이름과 코드 문자열이 같아야 하므로, enum 상수 이름이 겹칠 수 없는 것처럼 코드 문자열도 겹치지 않는다.
 * 위반이 여러 개면 한 번에 모두 보고한다.
 */
class ErrorCodeConventionTest {

    /** API 명세서(추가 기능 명세 포함)에 나온 도메인 접두어. 새 도메인을 추가하면 여기에도 추가한다. */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "COMMON", "AUTH", "USER", "CLICK", "CLICKER", "ITEM", "UNIV", "ALLY", "MATCH", "GUEST", "PAY", "FILE", "SUPPORT",
            "ABUSE", "ATTACK", // 어뷰징 의심 시 봇 확인 (SCRUM-115)
            "CHAT", // 채팅 (추가 명세 §3-7, SCRUM-128)
            "ASSIST"); // 연합 학교 지원 (추가 명세 §3-3, SCRUM-187)

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z]+(_[A-Z0-9]+)+$");

    @Test
    @DisplayName("상수 이름과 code 문자열이 같다")
    void codeEqualsConstantName() {
        assertSoftly(softly -> {
            for (ErrorCode errorCode : ErrorCode.values()) {
                softly.assertThat(errorCode.getCode())
                        .withFailMessage("ErrorCode.%s의 code가 \"%s\"입니다. 상수 이름과 같은 \"%s\"로 바꾸세요.",
                                errorCode.name(), errorCode.getCode(), errorCode.name())
                        .isEqualTo(errorCode.name());
            }
        });
    }

    @Test
    @DisplayName("코드는 '도메인접두어_의미' 대문자 스네이크 형식이고 접두어는 정해진 목록 중 하나다")
    void codeHasAllowedDomainPrefix() {
        assertSoftly(softly -> {
            for (ErrorCode errorCode : ErrorCode.values()) {
                String code = errorCode.name();
                if (!UPPER_SNAKE.matcher(code).matches()) {
                    softly.fail("ErrorCode.%s: '도메인접두어_의미' 대문자 스네이크 형식으로 쓰세요. 예: USER_NOT_FOUND", code);
                    continue;
                }
                String prefix = code.substring(0, code.indexOf('_'));
                softly.assertThat(ALLOWED_PREFIXES)
                        .withFailMessage("ErrorCode.%s: 접두어 '%s'는 허용 목록 %s에 없습니다. "
                                        + "오타가 아니라 새 도메인이면 ErrorCodeConventionTest.ALLOWED_PREFIXES에 추가하세요.",
                                code, prefix, ALLOWED_PREFIXES)
                        .contains(prefix);
            }
        });
    }

    @Test
    @DisplayName("오류 코드는 4xx·5xx 상태와 비어 있지 않은 메시지를 가진다")
    void hasErrorStatusAndMessage() {
        assertSoftly(softly -> {
            for (ErrorCode errorCode : ErrorCode.values()) {
                softly.assertThat(errorCode.getStatus().isError())
                        .withFailMessage("ErrorCode.%s: 상태 %s는 오류 상태(4xx·5xx)가 아닙니다.",
                                errorCode.name(), errorCode.getStatus())
                        .isTrue();
                softly.assertThat(errorCode.getMessage())
                        .withFailMessage("ErrorCode.%s: 사용자에게 보여 줄 message를 채우세요.", errorCode.name())
                        .isNotBlank();
            }
        });
    }
}
