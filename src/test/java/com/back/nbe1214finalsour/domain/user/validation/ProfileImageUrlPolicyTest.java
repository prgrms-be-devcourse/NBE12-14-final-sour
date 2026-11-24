package com.back.nbe1214finalsour.domain.user.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 프로필 이미지 URL 규칙. 프로필 수정에서 이미지 변경을 없애면서 (SCRUM-107) 그 통합 테스트에 있던 규칙 사례를 여기로 옮겼다.
 * 지금은 개인 로고 변경권 구매({@code CreditPurchaseService})가 이 정책을 쓴다.
 */
class ProfileImageUrlPolicyTest {

    private static final long USER_ID = 1024L;
    private static final String CDN = "https://cdn.devcproject.cloud";

    private final ProfileImageUrlPolicy policy = new ProfileImageUrlPolicy(CDN);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://cdn.devcproject.cloud/profile/1024/3f9a7c2e-1b4d-4e8f-9a0b-1c2d3e4f5a6b.webp",
            "https://cdn.devcproject.cloud/profile/1024/me.jpg",
            "https://cdn.devcproject.cloud/profile/1024/me.jpeg",
            "https://cdn.devcproject.cloud/profile/1024/me_1-a.png"})
    @DisplayName("우리 CDN의 내 profile 경로에 있는 png·jpg·jpeg·webp 파일은 허용한다")
    void allowsOwnProfilePath(String url) {
        assertThat(policy.isAllowed(url, USER_ID)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://cdn.devcproject.cloud/profile/999999/a.png",       // 다른 회원 경로
            "https://cdn.devcproject.cloud/profile/10245/a.png",        // 내 ID로 시작하는 다른 회원 경로
            "https://evil.example.com/profile/1024/a.png",              // 우리 CDN이 아님
            "http://cdn.devcproject.cloud/profile/1024/a.png",          // https가 아님
            "https://cdn.devcproject.cloud.evil.com/profile/1024/a.png", // 접두어만 같은 다른 호스트
            "https://cdn.devcproject.cloud/custom-logo/1024/a.png",     // 다른 용도
            "https://cdn.devcproject.cloud/profile/1024/../999999/a.png", // 경로 이동
            "https://cdn.devcproject.cloud/profile/1024/sub/a.png",     // 하위 경로
            "https://cdn.devcproject.cloud/profile/1024/a.png?x=1",     // 쿼리
            "https://cdn.devcproject.cloud/profile/1024/a.gif",         // PROFILE이 허용하지 않는 형식
            "https://cdn.devcproject.cloud/profile/1024/",              // 파일 이름 없음
            ""})
    @DisplayName("우리 CDN의 내 profile 경로가 아니면 거부한다")
    void rejectsOtherUrls(String url) {
        assertThat(policy.isAllowed(url, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("users.logo_url(500자)보다 긴 URL은 거부한다")
    void rejectsTooLongUrl() {
        String url = CDN + "/profile/" + USER_ID + "/" + "a".repeat(500) + ".png";

        assertThat(policy.isAllowed(url, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("설정한 공개 주소 끝의 /는 무시한다")
    void ignoresTrailingSlashOfBaseUrl() {
        ProfileImageUrlPolicy withSlash = new ProfileImageUrlPolicy(CDN + "/");

        assertThat(withSlash.isAllowed(CDN + "/profile/1024/a.png", USER_ID)).isTrue();
    }
}
