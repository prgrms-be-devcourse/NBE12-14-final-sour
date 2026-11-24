package com.back.nbe1214finalsour.domain.support.validation;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 문의 첨부 파일로 저장할 수 있는 URL인지 판단한다 (추가 명세 §3-8 "attachFileUrl은 3-2에서 발급받은 URL이어야 함").
 *
 * <p>허용 형식: {@code {file.public-base-url}/support/{userId}/{파일 이름}.{png|jpg|jpeg|pdf}}. 경로는 명세 응답 예시
 * ({@code https://cdn.devcproject.cloud/support/1024/a1b2.png})를 따르고, 형식은 업로드 목적 {@code SUPPORT_ATTACHMENT}의
 * 허용 형식(png, jpeg, pdf)이다 (계획 Q1·Q2). 파일 이름은 영문·숫자·{@code _}·{@code -}만 허용해 다른 회원 경로, 경로 이동
 * ({@code ..}), 하위 경로, 쿼리를 막는다.
 *
 * <p>업로드 모듈(3번, GitHub #70)이 아직 없어 {@code ProfileImageUrlPolicy}와 같은 방식으로 경로 규칙을 여기서 정했다.
 * 모듈이 생기면 그 규칙과 맞춘다. 파일이 실제로 S3에 있는지는 확인하지 않는다.
 */
@Component
public class SupportAttachmentUrlPolicy {

    /** {@code customer_support.attach_file_url VARCHAR(500)} */
    private static final int MAX_LENGTH = 500;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.(png|jpg|jpeg|pdf)");

    private final String publicBaseUrl;

    /** @param publicBaseUrl 업로드한 파일을 내려주는 공개 주소 (끝의 {@code /}는 무시) */
    public SupportAttachmentUrlPolicy(@Value("${file.public-base-url}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    public boolean isAllowed(String url, Long userId) {
        if (url.length() > MAX_LENGTH) {
            return false;
        }
        String prefix = publicBaseUrl + "/support/" + userId + "/";
        return url.startsWith(prefix) && FILE_NAME.matcher(url.substring(prefix.length())).matches();
    }
}
