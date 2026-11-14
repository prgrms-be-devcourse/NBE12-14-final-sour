package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityDetailResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.domain.university.service.UniversityQueryService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.*;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UniversityController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class UniversityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UniversityQueryService universityQueryService;

    @Test
    @DisplayName("학교 검색 시 200과 검색 결과를 반환한다")
    void searchUniversities() throws Exception {

        given(universityQueryService.searchUniversities("서울", 20))
                .willReturn(List.of(
                        new UniversitySearchResponse(12L, "서울대학교", null)
                ));

        mockMvc.perform(get("/api/v1/universities")
                        .param("keyword", "서울"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].name").value("서울대학교"))
                .andExpect(jsonPath("$.data[0].logoUrl").value((Object) null));

        verify(universityQueryService).searchUniversities("서울", 20);
    }

    @Test
    @DisplayName("size를 지정하면 해당 값으로 검색한다")
    void searchUniversities_customSize() throws Exception {

        given(universityQueryService.searchUniversities("서울", 5))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/universities")
                        .param("keyword", "서울")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(universityQueryService).searchUniversities("서울", 5);
    }

    @Test
    @DisplayName("size가 0이면 400을 반환한다")
    void searchUniversities_invalidSizeZero() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("keyword", "서울")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size가 100을 초과하면 400을 반환한다")
    void searchUniversities_invalidSizeOverMax() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("keyword", "서울")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색어가 공백이면 400을 반환한다")
    void searchUniversities_blankKeyword() throws Exception {
        mockMvc.perform(get("/api/v1/universities")
                        .param("keyword", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("학교 ID로 학과 목록을 조회하면 200과 학과 목록을 반환한다")
    void getDepartments() throws Exception {
        given(universityQueryService.getDepartments(12L))
                .willReturn(List.of(
                        new DepartmentResponse(301L, "경영학과"),
                        new DepartmentResponse(302L, "컴퓨터공학과")
                ));

        mockMvc.perform(get("/api/v1/universities/12/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(301))
                .andExpect(jsonPath("$.data[0].name").value("경영학과"))
                .andExpect(jsonPath("$.data[1].id").value(302))
                .andExpect(jsonPath("$.data[1].name").value("컴퓨터공학과"));

        verify(universityQueryService).getDepartments(12L);
    }

    @Test
    @DisplayName("존재하지 않는 학교의 학과를 조회하면 404를 반환한다")
    void getDepartments_universityNotFound() throws Exception {
        given(universityQueryService.getDepartments(999999L))
                .willThrow(new BusinessException(ErrorCode.UNIV_NOT_FOUND));

        mockMvc.perform(get("/api/v1/universities/999999/departments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));

        verify(universityQueryService).getDepartments(999999L);
    }

    @Test
    @DisplayName("비로그인 학교 상세 조회 시 200과 학교 정보를 반환한다")
    void getUniversityDetail() throws Exception {

        given(universityQueryService.getUniversityDetail(12L, null))
                .willReturn(new UniversityDetailResponse(
                        12L,
                        "서울대학교",
                        null,
                        15000L,
                        3L,
                        498L,
                        List.of(),
                        List.of(),
                        false
                ));

        mockMvc.perform(get("/api/v1/universities/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.name").value("서울대학교"))
                .andExpect(jsonPath("$.data.totalScore").value(15000))
                .andExpect(jsonPath("$.data.rank").value(3))
                .andExpect(jsonPath("$.data.memberCount").value(498))
                .andExpect(jsonPath("$.data.topDepartments").isArray())
                .andExpect(jsonPath("$.data.topContributors").isArray())
                .andExpect(jsonPath("$.data.isMyUniversity").value(false));

        verify(universityQueryService)
                .getUniversityDetail(12L, null);
    }

    @Test
    @DisplayName("존재하지 않는 학교 상세 조회 시 404를 반환한다")
    void getUniversityDetail_notFound() throws Exception {

        given(universityQueryService.getUniversityDetail(999999L, null))
                .willThrow(new BusinessException(ErrorCode.UNIV_NOT_FOUND));

        mockMvc.perform(get("/api/v1/universities/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));

        verify(universityQueryService)
                .getUniversityDetail(999999L, null);
    }

    @Test
    @DisplayName("학교 상세 조회 시 상위 학과와 기여자 정보를 반환한다")
    void getUniversityDetail_withRankings() throws Exception {

        given(universityQueryService.getUniversityDetail(12L, null))
                .willReturn(new UniversityDetailResponse(
                        12L,
                        "서울대학교",
                        null,
                        15000L,
                        3L,
                        498L,
                        List.of(
                                new UniversityDetailResponse.TopDepartment(
                                        1L, 301L, "경영학과", 5000L
                                ),
                                new UniversityDetailResponse.TopDepartment(
                                        2L, 302L, "컴퓨터공학과", 4000L
                                )
                        ),
                        List.of(
                                new UniversityDetailResponse.TopContributor(
                                        1L, 101L, "클릭장인", 9000L
                                )
                        ),
                        false
                ));

        mockMvc.perform(get("/api/v1/universities/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topDepartments.length()").value(2))
                .andExpect(jsonPath("$.data.topDepartments[0].rank").value(1))
                .andExpect(jsonPath("$.data.topDepartments[0].id").value(301))
                .andExpect(jsonPath("$.data.topDepartments[0].name").value("경영학과"))
                .andExpect(jsonPath("$.data.topDepartments[0].totalScore").value(5000))
                .andExpect(jsonPath("$.data.topContributors.length()").value(1))
                .andExpect(jsonPath("$.data.topContributors[0].rank").value(1))
                .andExpect(jsonPath("$.data.topContributors[0].userId").value(101))
                .andExpect(jsonPath("$.data.topContributors[0].nickname").value("클릭장인"))
                .andExpect(jsonPath("$.data.topContributors[0].contribScore").value(9000));

        verify(universityQueryService).getUniversityDetail(12L, null);
    }

    @Test
    @DisplayName("로그인 사용자가 자기 학교를 조회하면 isMyUniversity가 true이다")
    void getUniversityDetail_myUniversity() throws Exception {

        AuthUser authUser = new AuthUser(101L, 12L, 34L, Role.USER);

        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        authUser,
                        null,
                        List.of()
                );

        given(universityQueryService.getUniversityDetail(12L, 12L))
                .willReturn(new UniversityDetailResponse(
                        12L,
                        "서울대학교",
                        null,
                        15000L,
                        3L,
                        498L,
                        List.of(),
                        List.of(),
                        true
                ));

        mockMvc.perform(
                        get("/api/v1/universities/12")
                                .with(authentication(authentication))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMyUniversity").value(true));

        verify(universityQueryService)
                .getUniversityDetail(12L, 12L);
    }

    @Test
    @DisplayName("다른 학교 소속 사용자가 조회하면 isMyUniversity가 false이다")
    void getUniversityDetail_otherUniversity() throws Exception {

        // given
        // 로그인한 사용자는 9번 학교 소속
        AuthUser authUser = new AuthUser(101L, 9L, 34L, Role.USER);

        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        authUser,
                        null,
                        List.of()
                );

        // 조회 대상은 12번 학교
        given(universityQueryService.getUniversityDetail(12L, 9L))
                .willReturn(new UniversityDetailResponse(
                        12L,
                        "서울대학교",
                        null,
                        15000L,
                        3L,
                        498L,
                        List.of(),
                        List.of(),
                        false
                ));

        // when & then
        mockMvc.perform(
                        get("/api/v1/universities/12")
                                .with(authentication(authentication))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMyUniversity").value(false));

        verify(universityQueryService)
                .getUniversityDetail(12L, 9L);
    }
}
