package com.back.nbe1214finalsour.domain.user.validation;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 프로필 이미지로 저장할 수 있는 URL인지 판단한다 (추가 명세 §3-1 "{@code profileImageUrl}은 3-2에서 발급받은 우리 S3 URL만").
 * 프로필 이미지는 개인 로고 변경권({@code LOGO_PROFILE}) 구매로만 바꾸므로 {@code CreditPurchaseService}가 쓴다 (SCRUM-105).
 *
 * <p>허용 형식: {@code {file.public-base-url}/profile/{userId}/{파일 이름}.{png|jpg|jpeg|webp}}.
 * 업로드 URL 발급(3-2)의 저장 키 {@code {purpose}/{userId}/{UUID}.{확장자}}와 응답 예시
 * ({@code https://cdn.devcproject.cloud/profile/1024/3f9a.png})를 따른다. 파일 이름은 영문·숫자·{@code _}·{@code -}만
 * 허용해 다른 회원 경로, 경로 이동({@code ..}), 하위 경로, 쿼리를 막는다.
 *
 * <p>업로드 모듈(3번)이 아직 없어 경로 규칙을 여기서 정했다. 모듈이 생기면 그 규칙과 맞추고, 가능하면 이 판단을 모듈로 옮긴다
 * (계획 Q2). 파일이 실제로 S3에 있는지는 확인하지 않는다.
 */
@Component
public class ProfileImageUrlPolicy {

    /** {@code users.logo_url VARCHAR(500)} */
    private static final int MAX_LENGTH = 500;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.(png|jpg|jpeg|webp)");

    private final String publicBaseUrl;

    /** @param publicBaseUrl 업로드한 파일을 내려주는 공개 주소 (끝의 {@code /}는 무시) */
    public ProfileImageUrlPolicy(@Value("${file.public-base-url}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    public boolean isAllowed(String url, Long userId) {
        if (url.length() > MAX_LENGTH) {
            return false;
        }
        String prefix = publicBaseUrl + "/profile/" + userId + "/";
        return url.startsWith(prefix) && FILE_NAME.matcher(url.substring(prefix.length())).matches();
    }
}
