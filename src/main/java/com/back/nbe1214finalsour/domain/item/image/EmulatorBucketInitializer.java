package com.back.nbe1214finalsour.domain.item.image;

import io.awspring.cloud.autoconfigure.core.AwsConnectionDetails;
import io.awspring.cloud.s3.S3Template;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// 에뮬레이터(Floci)에 연결됐을 때만 로고 버킷을 만든다 (로컬 docker compose, 통합 테스트).
// 운영에는 에뮬레이터 연결 정보가 없으므로 아무것도 하지 않는다. 운영 버킷은 AWS에서 미리 만든다
@Component
public class EmulatorBucketInitializer implements ApplicationRunner {

    private final ObjectProvider<AwsConnectionDetails> emulatorConnection;
    private final S3Template s3Template;
    private final String bucket;

    public EmulatorBucketInitializer(ObjectProvider<AwsConnectionDetails> emulatorConnection,
                                     S3Template s3Template,
                                     @Value("${file.logo-bucket}") String bucket) {
        this.emulatorConnection = emulatorConnection;
        this.s3Template = s3Template;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (this.emulatorConnection.getIfAvailable() == null) {
            return;
        }
        if (!this.s3Template.bucketExists(this.bucket)) {
            this.s3Template.createBucket(this.bucket);
        }
    }
}