package com.back.nbe1214finalsour.domain.item.image;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

// S3(로컬·테스트는 Floci)에 로고 이미지를 저장한다
@Component
public class S3ImageStorage implements ImageStorage {

    private final S3Template s3Template;
    private final String bucket;
    private final String publicBaseUrl;

    public S3ImageStorage(
            S3Template s3Template,
            @Value("${file.logo-bucket}") String bucket,
            @Value("${file.logo-public-base-url}") String publicBaseUrl
    ) {
        this.s3Template = s3Template;
        this.bucket = bucket;
        // 설정 끝에 /가 있어도 주소에 //가 생기지 않게
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    @Override
    public String upload(String key, MultipartFile file, ImageType type) {
        // Content-Type은 클라이언트 값이 아니라 판별한 형식으로 (브라우저가 이 값으로 파일을 해석함)
        ObjectMetadata metadata = ObjectMetadata.builder()
                .contentType(type.getContentType())
                .build();

        try (InputStream in = file.getInputStream()) {
            this.s3Template.upload(this.bucket, key, in, metadata);
        } catch (IOException e) {
            // 서버가 받은 업로드 파일을 못 읽는 건 클라이언트 잘못이 아니므로 500
            throw new UncheckedIOException("로고 이미지를 읽지 못함", e);
        }
        return this.publicBaseUrl + "/" + key;
    }

    @Override
    public void delete(String key) {
        this.s3Template.deleteObject(this.bucket, key);
    }
}