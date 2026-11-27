package com.back.nbe1214finalsour.domain.user.devaccount;

import com.back.nbe1214finalsour.global.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포 설정 파일의 개발자 계정 기본값 (SCRUM-237). 환경 변수 없이 파일만 읽어 확인한다.
 * 로컬(기본 프로필)은 켜짐·ADMIN, 운영(prod)은 꺼짐·USER이고, 이메일·비밀번호는 어디에도 기본값이 없다.
 */
class DevAccountPropertiesProfileTest {

    @Test
    @DisplayName("기본 프로필은 켜짐·ADMIN이고 와플대학교 운영팀 소속이며, 이메일·비밀번호 기본값이 없다")
    void defaultProfile() throws IOException {
        DevAccountProperties properties = bind("application.yaml");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.hasEmail()).isFalse();
        assertThat(properties.role()).isEqualTo(Role.ADMIN);
        assertThat(properties.universityName()).isEqualTo("와플대학교");
        assertThat(properties.departmentName()).isEqualTo("운영팀");
        assertThat(properties.hasPassword()).isFalse();
        assertThat(properties.demoCount()).as("로컬은 시연용 계정 100개").isEqualTo(100);
    }

    @Test
    @DisplayName("prod 프로필은 꺼짐·USER이고, 이메일·비밀번호 기본값이 없다")
    void prodProfile() throws IOException {
        DevAccountProperties properties = bind("application-prod.yaml", "application.yaml");

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.hasEmail()).isFalse();
        assertThat(properties.role()).isEqualTo(Role.USER);
        assertThat(properties.hasPassword()).isFalse();
        assertThat(properties.demoCount()).as("운영은 DEV_ACCOUNT_DEMO_COUNT를 넣을 때만 만든다").isZero();
    }

    /** 앞에 둔 파일이 우선한다 (Spring이 프로필 파일을 기본 파일 위에 얹는 순서와 같다) */
    private static DevAccountProperties bind(String... files) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // 셸의 DEV_ACCOUNT_* 값이 섞이지 않게 파일만 남긴다
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : files) {
            List<PropertySource<?>> sources = loader.load(file, new ClassPathResource(file));
            sources.forEach(environment.getPropertySources()::addLast);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(environment.getPropertySources()),
                new PropertySourcesPlaceholdersResolver(environment));
        return binder.bind("dev-account", DevAccountProperties.class).get();
    }
}
