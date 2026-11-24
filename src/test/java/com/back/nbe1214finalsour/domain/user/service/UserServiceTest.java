package com.back.nbe1214finalsour.domain.user.service;

import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserScoreCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long DEPARTMENT_ID = 5L;

    // 레벨표: 1레벨(기본) → 2레벨(300) → 3레벨(1000)
    private static final List<Clicker> LEVEL_TABLE = List.of(
            new Clicker(1L, 10, 0L),
            new Clicker(2L, 20, 300L),
            new Clicker(3L, 40, 1000L)
    );

    @Mock
    private UserRepository userRepository;

    @Mock
    private UniversityRepository universityRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private ClickerService clickerService;

    @Mock
    private UserScoreCache userScoreCache;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private Clock clock;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserClickerLevelCache userClickerLevelCache;

    @Mock
    private User user;

    @Mock
    private University university;

    @Mock
    private Department department;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("내 정보를 조회하면 Redis 레벨로 clicker를 만든다")
    void getMyInfo_usesRedisLevel() {
        // given: DB의 clickerId는 1이지만 Redis 원본 레벨은 2 (업그레이드 직후, 배치 전)
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getUniversityId()).willReturn(UNIVERSITY_ID);
        given(user.getDepartmentId()).willReturn(DEPARTMENT_ID);
        given(user.getClickerId()).willReturn(1L);
        given(universityRepository.findById(UNIVERSITY_ID)).willReturn(Optional.of(university));
        given(departmentRepository.findById(DEPARTMENT_ID)).willReturn(Optional.of(department));
        given(userScoreCache.findContribScore(UNIVERSITY_ID, USER_ID)).willReturn(OptionalLong.empty());
        given(userScoreCache.findAttackScore(USER_ID)).willReturn(OptionalLong.empty());
        given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
        given(userClickerLevelCache.find(USER_ID)).willReturn(Optional.of(2L));

        // when
        MyInfoResponse response = userService.getMyInfo(USER_ID);

        // then
        assertThat(response.clicker().level()).isEqualTo(2L);
        assertThat(response.clicker().clickPower()).isEqualTo(20);
        assertThat(response.clicker().nextLevelRequiredScore()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("내 정보를 조회할 때 Redis에 레벨이 없으면 DB 레벨로 clicker를 만든다")
    void getMyInfo_fallsBackToDbLevel() {
        // given: Redis 레벨 키 없음 (처음 가입, Redis 재시작), DB clickerId는 1
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getUniversityId()).willReturn(UNIVERSITY_ID);
        given(user.getDepartmentId()).willReturn(DEPARTMENT_ID);
        given(user.getClickerId()).willReturn(1L);
        given(universityRepository.findById(UNIVERSITY_ID)).willReturn(Optional.of(university));
        given(departmentRepository.findById(DEPARTMENT_ID)).willReturn(Optional.of(department));
        given(userScoreCache.findContribScore(UNIVERSITY_ID, USER_ID)).willReturn(OptionalLong.empty());
        given(userScoreCache.findAttackScore(USER_ID)).willReturn(OptionalLong.empty());
        given(clickerService.getLevelTable()).willReturn(LEVEL_TABLE);
        given(userClickerLevelCache.find(USER_ID)).willReturn(Optional.empty());

        // when
        MyInfoResponse response = userService.getMyInfo(USER_ID);

        // then
        assertThat(response.clicker().level()).isEqualTo(1L);
        assertThat(response.clicker().clickPower()).isEqualTo(10);
        assertThat(response.clicker().nextLevelRequiredScore()).isEqualTo(300L);
    }
}
