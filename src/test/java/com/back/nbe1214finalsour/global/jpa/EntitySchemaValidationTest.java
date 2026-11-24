package com.back.nbe1214finalsour.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.entity.RefreshToken;
import com.back.nbe1214finalsour.domain.auth.entity.UniDomain;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.user.entity.User;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 엔티티 매핑이 Flyway로 만든 실제 스키마와 맞는지 Hibernate 스키마 검증({@code ddl-auto=validate})으로 확인한다.
 * 테이블·열이 없거나 타입이 다르면 컨텍스트가 뜨지 않아 이 테스트가 실패한다.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class EntitySchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("회원·인증·문의 엔티티가 ddl-auto=validate를 통과한다")
    void entitiesMatchSchema() {
        List<Class<?>> entities = entityManagerFactory.getMetamodel().getEntities().stream()
                .<Class<?>>map(Type::getJavaType)
                .toList();

        assertThat(entities).contains(User.class, UniDomain.class, RefreshToken.class, CustomerSupport.class);
    }
}
