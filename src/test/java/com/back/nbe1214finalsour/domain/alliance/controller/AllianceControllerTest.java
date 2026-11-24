package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
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
import java.time.OffsetDateTime;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AllianceControllerTest {

    private static final AuthUser USER = new AuthUser(1L, 10L, 20L, Role.USER);
    private static final String VALID_REQUEST = """
            {
                "name": "테스트연합",
                "durationHours": 24
            }
            """;

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
    @DisplayName("로그인 사용자가 연합 상세를 조회하면 200과 연합 정보를 반환한다")
    void getAlliance() throws Exception {
        Alliance alliance = Alliance.create("테스트연합", 24, OffsetDateTime.parse("2026-11-15T09:00:00+09:00"));
        given(allianceQueryService.getAlliance(100L)).willReturn(alliance);

        mockMvc.perform(get("/api/v1/alliances/100")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("테스트연합"))
                .andExpect(jsonPath("$.data.totalScore").value(0))
                .andExpect(jsonPath("$.data.durationHours").value(24))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.expiredAt").exists());

        verify(allianceQueryService).getAlliance(100L);
    }

    @Test
    @DisplayName("내 학교의 연합은 토큰의 학교 ID로 조회하고, 없으면 data가 null이다")
    void getMyAlliance() throws Exception {
        Alliance alliance = Alliance.create("우리연합", 24, OffsetDateTime.parse("2026-11-15T09:00:00+09:00"));
        given(allianceQueryService.findCurrentAlliance(10L)).willReturn(Optional.of(alliance), Optional.empty());

        mockMvc.perform(get("/api/v1/alliances/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("우리연합"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/alliances/me")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("내 학교의 연합은 로그인이 필요하다")
    void getMyAlliance_requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/alliances/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(allianceQueryService);
    }

    @Test
    @DisplayName("존재하지 않는 연합 상세 조회는 404를 반환한다")
    void getAlliance_notFound() throws Exception {
        given(allianceQueryService.getAlliance(999L))
                .willThrow(new BusinessException(ErrorCode.ALLY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/alliances/999")
                        .header(HttpHeaders.AUTHORIZATION, authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALLY_NOT_FOUND"));

        verify(allianceQueryService).getAlliance(999L);
    }

    @Test
    @DisplayName("로그인 사용자가 연합을 생성하면 201과 생성 정보를 반환한다")
    void createAlliance() throws Exception {
        Alliance alliance = mock(Alliance.class);
        given(alliance.getId()).willReturn(100L);
        given(alliance.getName()).willReturn("테스트연합");
        given(alliance.getTotalScore()).willReturn(0L);
        given(alliance.getDurationHours()).willReturn(24);
        given(alliance.getStatus()).willReturn(AllianceStatus.ACTIVE);
        given(alliance.getExpiredAt()).willReturn(OffsetDateTime.parse("2026-11-16T09:00:00+09:00"));
        given(allianceService.createAlliance(1L, "테스트연합", 24)).willReturn(alliance);

        mockMvc.perform(post("/api/v1/alliances")
                        .header(HttpHeaders.AUTHORIZATION, authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("테스트연합"))
                .andExpect(jsonPath("$.data.totalScore").value(0))
                .andExpect(jsonPath("$.data.durationHours").value(24))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.expiredAt").exists());

        verify(allianceService).createAlliance(1L, "테스트연합", 24);
    }

    @Test
    @DisplayName("연합 생성·상세 조회는 로그인하지 않으면 401을 반환한다")
    void authenticationRequired() throws Exception {
        mockMvc.perform(get("/api/v1/alliances/100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/alliances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verifyNoInteractions(allianceService, allianceQueryService);
    }

    @Test
    @DisplayName("유지 시간이 6시간 미만이면 400과 필드 오류를 반환한다")
    void createAlliance_invalidDuration() throws Exception {
        mockMvc.perform(post("/api/v1/alliances")
                        .header(HttpHeaders.AUTHORIZATION, authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"테스트연합","durationHours":3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("durationHours"));

        verifyNoInteractions(allianceService);
    }

    @Test
    @DisplayName("연합 이름이 공백이면 400과 필드 오류를 반환한다")
    void createAlliance_blankName() throws Exception {
        mockMvc.perform(post("/api/v1/alliances")
                        .header(HttpHeaders.AUTHORIZATION, authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","durationHours":24}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("name"));

        verifyNoInteractions(allianceService);
    }

    private String authorization() {
        return "Bearer " + jwtProvider.createAccessToken(USER, null);
    }
}
