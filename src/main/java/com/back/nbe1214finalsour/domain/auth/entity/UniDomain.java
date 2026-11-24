package com.back.nbe1214finalsour.domain.auth.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학교별 허용 이메일 도메인 ({@code uni_domain}). 인증 코드 요청 때 이메일 도메인으로 학교를 정한다.
 *
 * <p>마스터 데이터라 마이그레이션으로만 넣는다. 도메인은 소문자로 저장한다 ({@code ck_uni_domain_lowercase}).
 * 학교는 아직 엔티티 연관 대신 ID로 둔다 (학교 엔티티는 3번 담당 작업).
 */
@Entity
@Table(name = "uni_domain")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UniDomain extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "university_id", nullable = false)
    private Long universityId;

    @Column(nullable = false, length = 100)
    private String domain;
}
