package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UserRankingResponse;
import com.back.nbe1214finalsour.domain.university.service.DepartmentRankingQueryService;
import com.back.nbe1214finalsour.domain.university.service.UniversityRankingQueryService;
import com.back.nbe1214finalsour.domain.university.service.UserRankingQueryService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RankingController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class RankingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UniversityRankingQueryService rankingQueryService;

    @MockitoBean
    private DepartmentRankingQueryService departmentRankingQueryService;

    @MockitoBean
    private UserRankingQueryService userRankingQueryService;

    @Test
    @DisplayName("비로그인 사용자도 학교 랭킹을 조회할 수 있다")
    void getUniversities_anonymous() throws Exception {
        var response = new UniversityRankingResponse(
                List.of(new UniversityRankingResponse.Item(
                        1L, 12L, "서울대학교", null, 5000L
                )),
                0, 20, 1L, false, null
        );

        given(rankingQueryService.getUniversities(0, 20, null))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/rankings/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[0].universityId").value(12))
                .andExpect(jsonPath("$.data.items[0].name").value("서울대학교"))
                .andExpect(jsonPath("$.data.items[0].totalScore").value(5000))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.myUniversity").isEmpty());

        verify(rankingQueryService).getUniversities(0, 20, null);
    }

    @Test
    @DisplayName("로그인 사용자는 자기 학교 순위도 조회할 수 있다")
    void getUniversities_authenticated() throws Exception {
        var authUser = new AuthUser(
                101L, 12L, 301L, Role.USER
        );

        var response = new UniversityRankingResponse(
                List.of(),
                0, 20, 1L, false,
                new UniversityRankingResponse.MyUniversity(
                        1L, 12L, 5000L
                )
        );

        given(rankingQueryService.getUniversities(0, 20, 12L))
                .willReturn(response);

        mockMvc.perform(
                        get("/api/v1/rankings/universities")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + jwtProvider.createAccessToken(authUser)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myUniversity.rank").value(1))
                .andExpect(jsonPath("$.data.myUniversity.universityId").value(12))
                .andExpect(jsonPath("$.data.myUniversity.totalScore").value(5000));

        verify(rankingQueryService).getUniversities(0, 20, 12L);
    }

    @Test
    @DisplayName("음수 페이지를 요청하면 400을 반환한다")
    void getUniversities_invalidPage() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities")
                                .param("page", "-1")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rankingQueryService);
    }

    @Test
    @DisplayName("size가 100을 초과하면 400을 반환한다")
    void getUniversities_invalidSize() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities")
                                .param("size", "101")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rankingQueryService);
    }

    @Test
    @DisplayName("비로그인 사용자도 학과 랭킹을 조회할 수 있다")
    void getDepartmentRankings_anonymous() throws Exception {
        var response = new DepartmentRankingResponse(
                List.of(
                        new DepartmentRankingResponse.Item(
                                1L, 301L, "컴퓨터공학부", null, 5000L
                        ),
                        new DepartmentRankingResponse.Item(
                                2L, 302L, "경영학과", null, 3000L
                        )
                ),
                0, 20, 2L, false, null
        );

        given(departmentRankingQueryService.getDepartments(
                12L, 0, 20, null, null
        )).willReturn(response);

        mockMvc.perform(get(
                        "/api/v1/rankings/universities/12/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[0].departmentId").value(301))
                .andExpect(jsonPath("$.data.items[0].name").value("컴퓨터공학부"))
                .andExpect(jsonPath("$.data.items[0].totalScore").value(5000))
                .andExpect(jsonPath("$.data.items[1].rank").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.myDepartment").isEmpty());

        verify(departmentRankingQueryService)
                .getDepartments(12L, 0, 20, null, null);
    }

    @Test
    @DisplayName("로그인 사용자는 자신의 학과 순위도 조회한다")
    void getDepartmentRankings_authenticated() throws Exception {
        var authUser = new AuthUser(
                101L, 12L, 301L, Role.USER
        );

        var response = new DepartmentRankingResponse(
                List.of(),
                0, 20, 1L, false,
                new DepartmentRankingResponse.MyDepartment(
                        1L, 301L, 5000L
                )
        );

        given(departmentRankingQueryService.getDepartments(
                12L, 0, 20, 12L, 301L
        )).willReturn(response);

        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/departments")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + jwtProvider.createAccessToken(authUser)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myDepartment.rank").value(1))
                .andExpect(jsonPath("$.data.myDepartment.departmentId").value(301))
                .andExpect(jsonPath("$.data.myDepartment.totalScore").value(5000));

        verify(departmentRankingQueryService)
                .getDepartments(12L, 0, 20, 12L, 301L);
    }

    @Test
    @DisplayName("존재하지 않는 학교의 랭킹은 404를 반환한다")
    void getDepartmentRankings_notFound() throws Exception {
        given(departmentRankingQueryService.getDepartments(
                999L, 0, 20, null, null
        )).willThrow(
                new BusinessException(ErrorCode.UNIV_NOT_FOUND)
        );

        mockMvc.perform(get(
                        "/api/v1/rankings/universities/999/departments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));
    }

    @Test
    @DisplayName("학과 랭킹 size가 100을 초과하면 400을 반환한다")
    void getDepartmentRankings_invalidSize() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/departments")
                                .param("size", "101")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(departmentRankingQueryService);
    }

    @Test
    @DisplayName("비로그인 사용자도 개인 랭킹을 조회할 수 있다")
    void getUserRankings_anonymous() throws Exception {
        var response = new UserRankingResponse(
                List.of(
                        new UserRankingResponse.Item(
                                1L,
                                101L,
                                "클릭장인",
                                null,
                                new UserRankingResponse.DepartmentInfo(
                                        301L, "컴퓨터공학과"
                                ),
                                9000L
                        )
                ),
                0, 20, 1L, false, null
        );

        given(userRankingQueryService.getUsers(
                12L, 0, 20, null, null
        )).willReturn(response);

        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/users")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].rank").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(101))
                .andExpect(jsonPath("$.data.items[0].nickname").value("클릭장인"))
                .andExpect(jsonPath("$.data.items[0].department.id").value(301))
                .andExpect(jsonPath("$.data.items[0].department.name")
                        .value("컴퓨터공학과"))
                .andExpect(jsonPath("$.data.items[0].contribScore").value(9000))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.me").isEmpty());

        verify(userRankingQueryService)
                .getUsers(12L, 0, 20, null, null);
    }

    @Test
    @DisplayName("로그인 사용자는 자신의 개인 순위도 조회한다")
    void getUserRankings_authenticated() throws Exception {
        var authUser = new AuthUser(
                101L, 12L, 301L, Role.USER
        );

        var response = new UserRankingResponse(
                List.of(),
                0, 20, 1L, false,
                new UserRankingResponse.Me(
                        1L, 9000L
                )
        );

        given(userRankingQueryService.getUsers(
                12L, 0, 20, 12L, 101L
        )).willReturn(response);

        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/users")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + jwtProvider.createAccessToken(authUser)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.rank").value(1))
                .andExpect(jsonPath("$.data.me.contribScore").value(9000));

        verify(userRankingQueryService)
                .getUsers(12L, 0, 20, 12L, 101L);
    }

    @Test
    @DisplayName("다른 학교 랭킹 조회에도 로그인 사용자 정보가 전달된다")
    void getUserRankings_otherUniversity() throws Exception {
        var authUser = new AuthUser(
                101L, 99L, 901L, Role.USER
        );

        var response = new UserRankingResponse(
                List.of(),
                0, 20, 0L, false, null
        );

        given(userRankingQueryService.getUsers(
                12L, 0, 20, 99L, 101L
        )).willReturn(response);

        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/users")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + jwtProvider.createAccessToken(authUser)
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me").isEmpty());

        verify(userRankingQueryService)
                .getUsers(12L, 0, 20, 99L, 101L);
    }

    @Test
    @DisplayName("존재하지 않는 학교의 개인 랭킹은 404를 반환한다")
    void getUserRankings_notFound() throws Exception {
        given(userRankingQueryService.getUsers(
                999L, 0, 20, null, null
        )).willThrow(
                new BusinessException(ErrorCode.UNIV_NOT_FOUND)
        );

        mockMvc.perform(
                        get("/api/v1/rankings/universities/999/users")
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));
    }

    @Test
    @DisplayName("잘못된 페이지 크기는 400을 반환한다")
    void getUserRankings_invalidSize() throws Exception {
        mockMvc.perform(
                        get("/api/v1/rankings/universities/12/users")
                                .param("size", "101")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userRankingQueryService);
    }

}
