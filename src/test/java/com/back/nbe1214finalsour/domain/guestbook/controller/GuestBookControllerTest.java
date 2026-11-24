package com.back.nbe1214finalsour.domain.guestbook.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookQueryService;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookWriteService;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GuestBookController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtProvider.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiResponseWriter.class, GlobalExceptionHandler.class})
class GuestBookControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean GuestBookQueryService queryService;
    @MockitoBean GuestBookWriteService writeService;
    @MockitoBean LoginSessionStore loginSessionStore;

    @Test
    void anonymousCanRead_andUsesDefaults() throws Exception {
        given(queryService.getGuestBooks(12L, null, 20, null))
                .willReturn(new GuestBookListResponse(List.of(), null, false));

        mockMvc.perform(get("/api/v1/universities/12/guestbooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(queryService).getGuestBooks(12L, null, 20, null);
    }

    @Test
    void loggedInUser_passesViewerIdAndCursor() throws Exception {
        var principal = new AuthUser(5L, 7L, 2L, Role.USER);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        var item = new GuestBookListResponse.Item(10L, "응원", 1, true,
                new GuestBookListResponse.Author("테스트유저", "작성자대학교"),
                OffsetDateTime.parse("2026-11-18T10:00:00+09:00"));
        given(queryService.getGuestBooks(12L, 20L, 1, 5L))
                .willReturn(new GuestBookListResponse(List.of(item), null, false));

        mockMvc.perform(get("/api/v1/universities/12/guestbooks")
                        .with(authentication(auth))
                        .param("cursor", "20")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[0].author.universityName").value("작성자대학교"));
        verify(queryService).getGuestBooks(12L, 20L, 1, 5L);
    }

    @Test
    void loggedInUser_canCreate_andReceives201() throws Exception {
        var principal = new AuthUser(5L, 7L, 2L, Role.USER);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        var item = new GuestBookListResponse.Item(10L, "응원", 0, false,
                new GuestBookListResponse.Author("테스트유저", "작성자대학교"),
                OffsetDateTime.parse("2026-11-19T10:00:00+09:00"));
        given(writeService.create(12L, 5L, "응원")).willReturn(item);

        mockMvc.perform(post("/api/v1/universities/12/guestbooks")
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"응원\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.likedByMe").value(false));
        verify(writeService).create(12L, 5L, "응원");
    }

    @Test
    void anonymousCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/universities/12/guestbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"응원\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/api/v1/universities/12/guestbooks").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/universities/12/guestbooks").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/universities/12/guestbooks").param("cursor", "-1"))
                .andExpect(status().isBadRequest());
    }
}
