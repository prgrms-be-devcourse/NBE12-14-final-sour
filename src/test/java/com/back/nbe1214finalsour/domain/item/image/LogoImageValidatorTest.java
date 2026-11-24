package com.back.nbe1214finalsour.domain.item.image;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogoImageValidatorTest {

    private final LogoImageValidator validator = new LogoImageValidator();

    // 각 형식의 실제 파일 앞부분(매직 넘버) + 뒤에 아무 바이트
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2};
    private static final byte[] WEBP_BYTES = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1, 2};

    private static MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("logoImage", filename, contentType, content);
    }

    @Test
    @DisplayName("PNG 파일이면 PNG로 판별한다")
    void validate_png() {
        assertThat(this.validator.validate(file("logo.png", "image/png", PNG_BYTES)))
                .isEqualTo(ImageType.PNG);
    }

    @Test
    @DisplayName("JPEG 파일이면 JPEG로 판별한다")
    void validate_jpeg() {
        assertThat(this.validator.validate(file("logo.jpg", "image/jpeg", JPEG_BYTES)))
                .isEqualTo(ImageType.JPEG);
    }

    @Test
    @DisplayName("WEBP 파일이면 WEBP로 판별한다")
    void validate_webp() {
        assertThat(this.validator.validate(file("logo.webp", "image/webp", WEBP_BYTES)))
                .isEqualTo(ImageType.WEBP);
    }

    @Test
    @DisplayName("확장자·Content-Type과 상관없이 실제 내용으로 판별한다 (이름이 .gif여도 내용이 PNG면 PNG)")
    void validate_detectsByContent() {
        assertThat(this.validator.validate(file("logo.gif", "image/gif", PNG_BYTES)))
                .isEqualTo(ImageType.PNG);
    }

    @Test
    @DisplayName("파일이 없으면 ITEM_LOGO_IMAGE_REQUIRED")
    void validate_null() {
        assertThatThrownBy(() -> this.validator.validate(null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_REQUIRED);
    }

    @Test
    @DisplayName("빈 파일이면 ITEM_LOGO_IMAGE_REQUIRED")
    void validate_empty() {
        assertThatThrownBy(() -> this.validator.validate(file("logo.png", "image/png", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_REQUIRED);
    }

    @Test
    @DisplayName("5MB를 넘으면 ITEM_LOGO_IMAGE_TOO_LARGE")
    void validate_tooLarge() {
        byte[] content = new byte[(int) LogoImageValidator.MAX_SIZE_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, content, 0, PNG_BYTES.length);   // 형식은 정상인 PNG

        assertThatThrownBy(() -> this.validator.validate(file("logo.png", "image/png", content)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("정확히 5MB는 통과한다")
    void validate_maxSize() {
        byte[] content = new byte[(int) LogoImageValidator.MAX_SIZE_BYTES];
        System.arraycopy(PNG_BYTES, 0, content, 0, PNG_BYTES.length);

        assertThat(this.validator.validate(file("logo.png", "image/png", content)))
                .isEqualTo(ImageType.PNG);
    }

    @Test
    @DisplayName("이름과 Content-Type을 PNG로 속인 SVG는 ITEM_LOGO_IMAGE_INVALID_TYPE")
    void validate_svgDisguisedAsPng() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> this.validator.validate(file("logo.png", "image/png", svg)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_INVALID_TYPE);
    }

    @Test
    @DisplayName("형식을 판별할 만큼 바이트가 없으면 ITEM_LOGO_IMAGE_INVALID_TYPE")
    void validate_tooShort() {
        assertThatThrownBy(() -> this.validator.validate(file("logo.png", "image/png", new byte[]{(byte) 0x89, 0x50})))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_INVALID_TYPE);
    }
}