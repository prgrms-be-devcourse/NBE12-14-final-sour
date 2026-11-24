package com.back.nbe1214finalsour.domain.university.entity;

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

import java.math.BigDecimal;

@Entity
@Table(name = "university")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class University extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "total_score", nullable = false)
    private Long totalScore = 0L;

    @Column(name = "library_lat", precision = 9, scale = 6)
    private BigDecimal libraryLat;

    @Column(name = "library_lng", precision = 9, scale = 6)
    private BigDecimal libraryLng;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    /** 운영용 숨김 학교(와플대학교)면 true. 순위·지도·검색에 보이지 않는다 (V14, SCRUM-235) */
    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    public University(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("학교 이름은 비어 있을 수 없습니다.");
        }
        this.name = name;
    }

    public void changeLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }
}
