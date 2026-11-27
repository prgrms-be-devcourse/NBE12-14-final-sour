import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    java
    id("jacoco")
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.back"
version = "0.0.1-SNAPSHOT"
description = "NBE12-14-final-sour"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

jacoco {
    toolVersion = "0.8.16"
}

repositories {
    mavenCentral()
}

// Spring Cloud AWS 모듈 버전을 한곳에서 맞춘다 (S3 로고 이미지 업로드)
dependencyManagement {
    imports {
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.2.1")
    }
}

dependencies {
    annotationProcessor("org.projectlombok:lombok")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    developmentOnly("io.awspring.cloud:spring-cloud-aws-docker-compose")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    // jjwt-jackson은 Jackson 2(com.fasterxml)를 끌어온다. 프로젝트는 Jackson 3이므로 Gson 모듈을 쓴다
    runtimeOnly("io.jsonwebtoken:jjwt-gson:0.13.0")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    // API 문서화, Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")

    testAnnotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-mail-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("io.awspring.cloud:spring-cloud-aws-testcontainers")
    testImplementation("io.floci:testcontainers-floci:2.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    testLogging {
        events("failed")                            // 실패 시 원인(스택트레이스)만 Gradle이 출력
        exceptionFormat = TestExceptionFormat.FULL
    }

    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            val (color, label) = when (result.resultType) {
                TestResult.ResultType.SUCCESS -> "\u001B[32m" to "PASSED"   // 초록
                TestResult.ResultType.FAILURE -> "\u001B[31m" to "FAILED"   // 빨강
                else -> "\u001B[33m" to "SKIPPED"                           // 노랑
            }
            println("$color$label\u001B[0m ${testDescriptor.className} > ${testDescriptor.displayName}")
        }
    })
}

// ./gradlew test → 단위 테스트만 (Docker 불필요)
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // ContainerImageVersionTest가 읽는다. 입력으로 등록해야 compose.yaml만 바뀌어도 테스트가 다시 돈다
    inputs.file("compose.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
}

// ./gradlew integrationTest → 통합 테스트만 (Docker 필요)
val integrationTest by tasks.registering(Test::class) {
    description = "통합 테스트"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

// ./gradlew build → 둘 다 실행
tasks.check {
    dependsOn(integrationTest)
}

val testTasks = tasks.withType<Test>()

val testCoverageData = files()

testTasks.configureEach {
    val jacocoExt = extensions.getByType<JacocoTaskExtension>()
    testCoverageData.from(provider { jacocoExt.destinationFile })
}

tasks.register<JacocoReport>("codeCoverageReport") {
    dependsOn(testTasks)
    executionData(testCoverageData)
    sourceSets(sourceSets.main.get())

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}