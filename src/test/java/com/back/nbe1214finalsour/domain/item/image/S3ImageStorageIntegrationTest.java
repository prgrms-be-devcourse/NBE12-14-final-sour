package com.back.nbe1214finalsour.domain.item.image;

import com.back.nbe1214finalsour.IntegrationTest;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 S3 대신 Floci(TestcontainersConfiguration)에 올리고 지운다
@IntegrationTest
class S3ImageStorageIntegrationTest {

    @Autowired
    private ImageStorage imageStorage;

    // 저장 결과를 우리 코드와 상관없이 직접 확인하는 용도
    @Autowired
    private S3Template s3Template;

    @Value("${file.logo-bucket}")
    private String bucket;

    @Value("${file.logo-public-base-url}")
    private String publicBaseUrl;

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2};

    // 테스트끼리 같은 파일을 건드리지 않도록 매번 다른 경로
    private String newKey() {
        return "custom-logo/1/" + UUID.randomUUID() + ".png";
    }

    @Test
    @DisplayName("업로드하면 버킷에 같은 내용으로 저장되고 공개 주소(공개 주소 + / + 경로)를 돌려준다")
    void upload_storesFileAndReturnsPublicUrl() throws IOException {
        String key = newKey();

        String url = this.imageStorage.upload(key, new MockMultipartFile("logoImage", "logo.png", "image/png", PNG_BYTES), ImageType.PNG);

        assertThat(url).isEqualTo(this.publicBaseUrl + "/" + key);
        assertThat(this.s3Template.objectExists(this.bucket, key)).isTrue();
        assertThat(this.s3Template.download(this.bucket, key).getInputStream().readAllBytes()).isEqualTo(PNG_BYTES);
    }

    @Test
    @DisplayName("Content-Type은 클라이언트가 보낸 값이 아니라 판별한 형식으로 저장한다")
    void upload_usesDetectedContentType() {
        String key = newKey();

        // 클라이언트는 image/gif라고 보냈지만 실제 내용은 PNG
        this.imageStorage.upload(key, new MockMultipartFile("logoImage", "logo.gif", "image/gif", PNG_BYTES), ImageType.PNG);

        assertThat(this.s3Template.download(this.bucket, key).contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("삭제하면 버킷에서 파일이 사라진다")
    void delete_removesFile() {
        String key = newKey();
        this.s3Template.upload(this.bucket, key, new ByteArrayInputStream(PNG_BYTES));   // 우리 코드 말고 직접 올려 둠

        this.imageStorage.delete(key);

        assertThat(this.s3Template.objectExists(this.bucket, key)).isFalse();
    }
}