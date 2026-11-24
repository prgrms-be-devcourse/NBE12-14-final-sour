package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchService;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.*;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
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

    private String token() { return "Bearer " + jwtProvider.createAccessToken(USER, null); }
}
