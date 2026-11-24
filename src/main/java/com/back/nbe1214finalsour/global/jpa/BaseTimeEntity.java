package com.back.nbe1214finalsour.global.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * {@code created_at}·{@code updated_at} 열이 있는 엔티티의 공통 부모.
 *
 * <p>두 시각은 DB가 정하고 JPA는 읽기만 한다. {@code created_at}은 열 기본값 {@code now()}가,
 * {@code updated_at}은 기본값과 {@code set_updated_at()} 트리거(V1__init.sql)가 채운다. JDBC로 직접 바꾸는 행도
 * 같은 트리거를 거치므로 모든 경로가 DB 시계 하나를 쓴다. Hibernate는 INSERT·UPDATE 뒤 DB가 정한 값을 다시 읽어
 * 엔티티에 채운다.
 *
 * <p>PostgreSQL의 {@code now()}는 트랜잭션 시작 시각이다. 그래서 한 트랜잭션 안에서 만들고 고친 행은
 * {@code created_at}과 {@code updated_at}이 같다.
 *
 * <p>열이 {@code TIMESTAMPTZ}이고 응답 시각도 오프셋을 포함하므로(API 명세서 §1) {@link OffsetDateTime}을 쓴다.
 * {@code id}와 {@code deleted_at}은 테이블마다 달라(복합 키, 일부만 soft delete) 넣지 않는다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
