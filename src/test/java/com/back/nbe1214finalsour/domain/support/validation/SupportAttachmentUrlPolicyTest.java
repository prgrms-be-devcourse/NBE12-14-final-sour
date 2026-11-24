package com.back.nbe1214finalsour.domain.support.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 문의 첨부 URL 규칙 (SCRUM-110, 계획 Q2) */
class SupportAttachmentUrlPolicyTest {

    private static final long USER_ID = 1024L;
    private static final String CDN = "https://cdn.devcproject.cloud";

    private final SupportAttachmentUrlPolicy policy = new SupportAttachmentUrlPolicy(CDN);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://cdn.devcproject.cloud/support/1024/a1b2.png",
            "https://cdn.devcproject.cloud/support/1024/3f9a7c2e-1b4d-4e8f-9a0b-1c2d3e4f5a6b.pdf",
            "https://cdn.devcproject.cloud/support/1024/me.jpg",
            "https://cdn.devcproject.cloud/support/1024/me_1-a.jpeg"})
    @DisplayName("우리 CDN의 내 support 경로에 있는 png·jpg·jpeg·pdf 파일은 허용한다")
    void allowsOwnSupportPath(String url) {
        assertThat(policy.isAllowed(url, USER_ID)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://cdn.devcproject.cloud/support/999999/a.png",         // 다른 회원 경로
            "https://cdn.devcproject.cloud/support/10245/a.png",          // 내 ID로 시작하는 다른 회원 경로
            "https://evil.example.com/support/1024/a.png",                // 우리 CDN이 아님
            "http://cdn.devcproject.cloud/support/1024/a.png",            // https가 아님
            "https://cdn.devcproject.cloud.evil.com/support/1024/a.png",  // 접두어만 같은 다른 호스트
            "https://cdn.devcproject.cloud/profile/1024/a.png",           // 다른 용도
            "https://cdn.devcproject.cloud/support_attachment/1024/a.png", // 저장 키 규칙 그대로의 경로 (명세 예시와 다름)
            "https://cdn.devcproject.cloud/support/1024/../999999/a.png", // 경로 이동
            "https://cdn.devcproject.cloud/support/1024/sub/a.png",       // 하위 경로
            "https://cdn.devcproject.cloud/support/1024/a.png?x=1",       // 쿼리
            "https://cdn.devcproject.cloud/support/1024/a.webp",          // 문의 첨부에 없는 형식
            "https://cdn.devcproject.cloud/support/1024/a.exe",
            "https://cdn.devcproject.cloud/support/1024/a.PNG",           // 확장자 대문자
            "https://cdn.devcproject.cloud/support/1024/.png"})           // 이름 없음
    @DisplayName("다른 회원·다른 호스트·다른 용도·경로 조작·허용되지 않은 형식은 거부한다")
    void rejectsOthers(String url) {
        assertThat(policy.isAllowed(url, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("500자를 넘으면 거부한다 (attach_file_url VARCHAR(500))")
    void rejectsTooLong() {
        String prefix = CDN + "/support/1024/";
        String ok = prefix + "a".repeat(500 - prefix.length() - 4) + ".png";
        assertThat(ok).hasSize(500);
        assertThat(policy.isAllowed(ok, USER_ID)).isTrue();
        assertThat(policy.isAllowed(prefix + "a".repeat(500 - prefix.length() - 3) + ".png", USER_ID)).isFalse();
    }

    @Test
    @DisplayName("공개 주소 끝의 /는 무시한다")
    void ignoresTrailingSlash() {
        assertThat(new SupportAttachmentUrlPolicy(CDN + "/").isAllowed(CDN + "/support/1024/a.png", USER_ID)).isTrue();
    }
}
