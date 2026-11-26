package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AllianceMatchController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtProvider.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiResponseWriter.class, GlobalExceptionHandler.class})
class AllianceMatchControllerTest {
    private static final AuthUser USER = new AuthUser(1L, 10L, 20L, Role.USER);

    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean LoginSessionStore loginSessionStore;
    @MockitoBean AllianceMatchService service;

    @Test
    void requestMatch_returnsCreated() throws Exception {
        AllianceMatch match = AllianceMatch.request(10L, 20L, 6, OffsetDateTime.now().plusHours(12));
        given(service.requestMatch(1L, 10L, 20L, 6, null)).willReturn(match);
        mvc.perform(post("/api/v1/alliances/10/matches")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opponentAllianceId\":20,\"durationHours\":6}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
        verify(service).requestMatch(1L, 10L, 20L, 6, null);
    }

    @Test
    void requestMatch_withScheduledStartAt_returnsReservationTime() throws Exception {
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-11-18T20:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(10L, 20L, 2,
                scheduledAt.minusHours(1), scheduledAt);
        given(service.requestMatch(1L, 10L, 20L, 2, scheduledAt)).willReturn(match);
        mvc.perform(post("/api/v1/alliances/10/matches")
                        .header(HttpHeaders.AUTHORIZATION, token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"opponentAllianceId\":20,\"durationHours\":2,\"scheduledStartAt\":\"2026-11-18T20:00:00+09:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scheduledStartAt").value("2026-11-18T20:00:00+09:00"));
        verify(service).requestMatch(1L, 10L, 20L, 2, scheduledAt);
    }

    @Test
    void noToken_cannotAcceptOrCancel() throws Exception {
        mvc.perform(patch("/api/v1/alliances/matches/50/accept"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/alliances/matches/50/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptMatch_returnsInProgress() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AllianceMatch match = AllianceMatch.request(10L, 20L, 6, now.plusHours(12));
        match.accept(now);
        given(service.acceptMatch(1L, 50L)).willReturn(match);
        mvc.perform(patch("/api/v1/alliances/matches/50/accept")
                        .header(HttpHeaders.AUTHORIZATION, token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        verify(service).acceptMatch(1L, 50L);
    }

    @Test
    @DisplayName("대결 신청을 취소하면 200과 CANCELED 상태를 반환한다")
    void cancelMatch_returnsCanceled() throws Exception {
        // given
        AllianceMatch match = AllianceMatch.request(10L, 20L, 6, OffsetDateTime.now().plusHours(12));
        match.cancelRequested();
        given(service.cancelMatch(1L, 50L)).willReturn(match);

        // when
        var result = mvc.perform(patch("/api/v1/alliances/matches/50/cancel")
                .header(HttpHeaders.AUTHORIZATION, token()));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
        verify(service).cancelMatch(1L, 50L);
    }

    @Test
    @DisplayName("대결 참가 연합의 소속 회원이 아니면 취소 요청에 403을 반환한다")
    void cancelMatch_notMember_returnsForbidden() throws Exception {
        // given
        given(service.cancelMatch(1L, 50L))
                .willThrow(new BusinessException(ErrorCode.ALLY_NOT_MEMBER));

        // when
        var result = mvc.perform(patch("/api/v1/alliances/matches/50/cancel")
                .header(HttpHeaders.AUTHORIZATION, token()));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ALLY_NOT_MEMBER"));
        verify(service).cancelMatch(1L, 50L);
    }

    @Test
    @DisplayName("대결 시간이 24시간을 초과하면 400을 반환하고 대결을 신청하지 않는다")
    void requestMatch_invalidDuration_returnsBadRequest() throws Exception {
        // given
        String request = """
                {"opponentAllianceId":20,"durationHours":25}
                """;

        // when
        var result = mvc.perform(post("/api/v1/alliances/10/matches")
                .header(HttpHeaders.AUTHORIZATION, token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("durationHours"));
        verifyNoInteractions(service);
    }

    private String token() { return "Bearer " + jwtProvider.createAccessToken(USER, null); }
}
