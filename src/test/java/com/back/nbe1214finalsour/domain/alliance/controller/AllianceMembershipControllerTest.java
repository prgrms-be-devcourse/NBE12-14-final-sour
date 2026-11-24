package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.ApiAccessDeniedHandler;
import com.back.nbe1214finalsour.global.security.ApiAuthenticationEntryPoint;
import com.back.nbe1214finalsour.global.security.ApiResponseWriter;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.CorsConfig;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.SecurityConfig;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AllianceController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class AllianceMembershipControllerTest {

    private static final AuthUser USER =
            new AuthUser(1L, 10L, 20L, Role.USER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private AllianceService allianceService;

    @MockitoBean
    private AllianceQueryService allianceQueryService;

    @Test
    @DisplayName("연합 가입 신청 시 201과 PENDING 상태를 반환한다")
    void applyToAlliance_success() throws Exception {
        // given
        AllianceUniversity application = mock(AllianceUniversity.class);

        given(application.getId()).willReturn(100L);
        given(application.getAllianceId()).willReturn(1L);
        given(application.getUniversityId()).willReturn(10L);
        given(application.getStatus())
                .willReturn(AllianceUniversityStatus.PENDING);

        given(allianceService.applyToAlliance(1L, 1L))
                .willReturn(application);

        // when & then
        mockMvc.perform(post("/api/v1/alliances/1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.allianceId").value(1))
                .andExpect(jsonPath("$.data.universityId").value(10))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        verify(allianceService).applyToAlliance(1L, 1L);
    }

    @Test
    @DisplayName("연합 가입 승인 시 200과 ACCEPTED 상태를 반환한다")
    void approveApplication_success() throws Exception {
        // given
        AllianceUniversity application = mock(AllianceUniversity.class);

        given(application.getId()).willReturn(100L);
        given(application.getAllianceId()).willReturn(1L);
        given(application.getUniversityId()).willReturn(20L);
        given(application.getStatus())
                .willReturn(AllianceUniversityStatus.ACCEPTED);

        given(allianceService.approveApplication(1L, 100L))
                .willReturn(application);

        // when & then
        mockMvc.perform(patch("/api/v1/alliances/applications/100/approve")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.allianceId").value(1))
                .andExpect(jsonPath("$.data.universityId").value(20))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        verify(allianceService).approveApplication(1L, 100L);
    }

    @Test
    @DisplayName("연합 가입 거절 시 200과 REJECTED 상태를 반환한다")
    void rejectApplication_success() throws Exception {
        // given
        AllianceUniversity application = mock(AllianceUniversity.class);

        given(application.getId()).willReturn(100L);
        given(application.getAllianceId()).willReturn(1L);
        given(application.getUniversityId()).willReturn(20L);
        given(application.getStatus())
                .willReturn(AllianceUniversityStatus.REJECTED);

        given(allianceService.rejectApplication(1L, 100L))
                .willReturn(application);

        // when & then
        mockMvc.perform(patch("/api/v1/alliances/applications/100/reject")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.allianceId").value(1))
                .andExpect(jsonPath("$.data.universityId").value(20))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(allianceService).rejectApplication(1L, 100L);
    }

    @Test
    @DisplayName("로그인하지 않은 사용자는 연합 가입 신청 및 승인 거절을 할 수 없다")
    void authenticationRequired() throws Exception {
        // 가입 신청
        mockMvc.perform(post("/api/v1/alliances/1/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("COMMON_UNAUTHORIZED"));

        // 가입 승인
        mockMvc.perform(patch("/api/v1/alliances/applications/100/approve"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("COMMON_UNAUTHORIZED"));

        // 가입 거절
        mockMvc.perform(patch("/api/v1/alliances/applications/100/reject"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("COMMON_UNAUTHORIZED"));

        verifyNoInteractions(allianceService);
    }

    @Test
    @DisplayName("연합 정원이 가득 차면 409를 반환한다")
    void applyToAlliance_full() throws Exception {
        // given
        given(allianceService.applyToAlliance(1L, 1L))
                .willThrow(new BusinessException(ErrorCode.ALLY_FULL));

        // when & then
        mockMvc.perform(post("/api/v1/alliances/1/applications")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLY_FULL"));
    }

    @Test
    @DisplayName("존재하지 않는 가입 신청을 승인하면 404를 반환한다")
    void approveApplication_notFound() throws Exception {
        // given
        given(allianceService.approveApplication(1L, 100L))
                .willThrow(new BusinessException(
                        ErrorCode.ALLY_APPLICATION_NOT_FOUND
                ));

        // when & then
        mockMvc.perform(patch("/api/v1/alliances/applications/100/approve")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("ALLY_APPLICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("연합 소속 회원이 아니면 거절 요청 시 403을 반환한다")
    void rejectApplication_notMember() throws Exception {
        // given
        given(allianceService.rejectApplication(1L, 100L))
                .willThrow(new BusinessException(ErrorCode.ALLY_NOT_MEMBER));

        // when & then
        mockMvc.perform(patch("/api/v1/alliances/applications/100/reject")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ALLY_NOT_MEMBER"));
    }

    @Test
    @DisplayName("이미 처리된 신청을 승인하면 409를 반환한다")
    void approveApplication_alreadyProcessed() throws Exception {
        // given
        given(allianceService.approveApplication(1L, 100L))
                .willThrow(new BusinessException(
                        ErrorCode.ALLY_APPLICATION_NOT_PENDING
                ));

        // when & then
        mockMvc.perform(patch("/api/v1/alliances/applications/100/approve")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ALLY_APPLICATION_NOT_PENDING"));
    }

    private String authorization() {
        return "Bearer " + jwtProvider.createAccessToken(USER, null);
    }
}