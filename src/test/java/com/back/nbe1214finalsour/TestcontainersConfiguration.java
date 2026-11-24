package com.back.nbe1214finalsour;

import io.floci.testcontainers.FlociContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공용 컨테이너.
 *
 * <p>컨테이너를 static 필드로 두고 {@code @ImportTestcontainers}로 가져온다. static이라 JVM에 하나씩만 생기므로,
 * 테스트 설정(@MockitoBean, RANDOM_PORT 등)이 달라 애플리케이션 컨텍스트가 여러 개 떠도 모든 컨텍스트가 같은 컨테이너를 쓴다.
 * ({@code @Bean}으로 두면 컨텍스트마다 컨테이너를 새로 띄운다)
 *
 * <p>주의: 컨텍스트가 닫히면 Spring Boot가 이 컨테이너들도 닫는다. 지금은 모든 컨텍스트가 테스트가 다 끝난 뒤에 닫혀서 문제가 없지만,
 * {@code @DirtiesContext}로 중간에 컨텍스트를 닫으면 그 뒤 테스트들이 꺼진 컨테이너에 붙어 실패한다. {@code @DirtiesContext}를 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportTestcontainers(TestcontainersConfiguration.Containers.class)
class TestcontainersConfiguration {

    // 인터페이스의 필드는 자동으로 public static final이다
    interface Containers {

        // 컨텍스트들이 DB 하나를 같이 쓴다. 컨텍스트마다 HikariCP 풀(최대 10개, 처음부터 채워 둠)이 있어서 기본 한도 100으로는 부족하다.
        // 스프링 테스트 컨텍스트 캐시 최대 32개 × 풀 10개 = 320을 넘게 잡는다. fsync=off는 Testcontainers 기본 명령을 유지한 것
        @ServiceConnection
        PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400");

        @ServiceConnection(name = "redis")
        GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);

        // S3 대신 쓰는 AWS 에뮬레이터 (로고 이미지 업로드). 인증 토큰 없이 쓸 수 있다
        @ServiceConnection
        FlociContainer FLOCI = new FlociContainer(DockerImageName.parse("floci/floci:latest"));
    }
}