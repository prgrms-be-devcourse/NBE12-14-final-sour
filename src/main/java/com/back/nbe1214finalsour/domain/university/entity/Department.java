package com.back.nbe1214finalsour.domain.university.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "department")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Department extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "university_id", nullable = false)
    private University university;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "total_score", nullable = false)
    private Long totalScore = 0L;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    public Department(University university, String name) {
        if (university == null) {
            throw new IllegalArgumentException("소속 학교는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("학과 이름은 비어 있을 수 없습니다.");
        }
        this.university = university;
        this.name = name;
    }

    public void changeLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }
}
