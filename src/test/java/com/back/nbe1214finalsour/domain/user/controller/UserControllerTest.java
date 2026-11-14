package com.back.nbe1214finalsour.domain.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse;
import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse.ClickerInfo;
import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse.DepartmentInfo;
import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse.UniversityInfo;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.service.UserService;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(UserController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
@ExtendWith(OutputCaptureExtension.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private UserService userService;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("[GET] /api/v1/users/nickname/availability는 토큰 없이 사용 가능 여부를 200으로 응답")
    void checkNickname() throws Exception {
        given(userService.isNicknameAvailable("대대대짱")).willReturn(false);

        mockMvc.perform(get("/api/v1/users/nickname/availability").param("nickname", "대대대짱"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @ParameterizedTest(name = "nickname=\"{0}\"")
    @ValueSource(strings = {"", "대", "가나다라마바사아자차카타파", " 대대대"})
    @DisplayName("[GET] /api/v1/users/nickname/availability는 닉네임 규칙(앞뒤 공백 없이 2~12자)을 어기면 400 COMMON_INVALID_INPUT")
    void checkNickname_invalid(String nickname) throws Exception {
        mockMvc.perform(get("/api/v1/users/nickname/availability").param("nickname", nickname))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("nickname"));

        verify(userService, never()).isNicknameAvailable(anyString());
    }

    @Test
    @DisplayName("[GET] /api/v1/users/nickname/availability는 nickname 파라미터가 없으면 400 COMMON_INVALID_INPUT")
    void checkNickname_missing() throws Exception {
        mockMvc.perform(get("/api/v1/users/nickname/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    // ===== 프로필 수정 (SCRUM-80) =====

    private static final String PROFILE_URL = "https://cdn.devcproject.cloud/profile/1024/3f9a.png";

    @Test
    @DisplayName("[PATCH] /api/v1/users/me는 서비스 결과를 내 정보 형식으로 200 응답")
    void updateProfile() throws Exception {
        given(userService.updateProfile(1024L, "대대대왕", PROFILE_URL)).willReturn(myInfo("대대대왕", PROFILE_URL));

        updateProfile("{\"nickname\":\"대대대왕\",\"profileImageUrl\":\"" + PROFILE_URL + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1024))
                .andExpect(jsonPath("$.data.nickname").value("대대대왕"))
                .andExpect(jsonPath("$.data.profileImageUrl").value(PROFILE_URL))
                .andExpect(jsonPath("$.data.university.name").value("서울대학교"))
                .andExpect(jsonPath("$.data.clicker.level").value(1));
    }

    @Test
    @DisplayName("[PATCH] /api/v1/users/me는 빠진 필드를 null로 넘긴다 (그대로 둠)")
    void updateProfile_missingFieldsAreNull() throws Exception {
        given(userService.updateProfile(1024L, null, null)).willReturn(myInfo("대대대짱", null));

        updateProfile("{}").andExpect(status().isOk());

        verify(userService).updateProfile(1024L, null, null);
    }

    @ParameterizedTest(name = "nickname=\"{0}\"")
    @ValueSource(strings = {"", "대", "가나다라마바사아자차카타파", " 대대대"})
    @DisplayName("[PATCH] /api/v1/users/me는 닉네임 규칙을 어기면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void updateProfile_invalidNickname(String nickname) throws Exception {
        updateProfile("{\"nickname\":\"" + nickname + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("nickname"));

        verify(userService, never()).updateProfile(any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profileErrors")
    @DisplayName("[PATCH] /api/v1/users/me의 도메인 오류는 명세서의 상태와 코드로 응답")
    void updateProfile_businessErrors(ErrorCode errorCode, int httpStatus) throws Exception {
        given(userService.updateProfile(any(), any(), isNull())).willThrow(new BusinessException(errorCode));

        updateProfile("{\"nickname\":\"대대대왕\"}")
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    static Stream<Arguments> profileErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.USER_NICKNAME_DUPLICATED, 409),
                Arguments.of(ErrorCode.FILE_INVALID_URL, 400));
    }

    @Test
    @DisplayName("[PATCH] /api/v1/users/me는 access 토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void updateProfile_requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"대대대왕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verify(userService, never()).updateProfile(any(), any(), any());
    }

    private ResultActions updateProfile(String body) throws Exception {
        String accessToken = jwtProvider.createAccessToken(new AuthUser(1024L, 12L, 34L, Role.USER), null);
        return mockMvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static MyInfoResponse myInfo(String nickname, String profileImageUrl) {
        return new MyInfoResponse(1024L, nickname, "student@snu.ac.kr", Role.USER, UserStatus.ACTIVE, profileImageUrl,
                new UniversityInfo(12L, "서울대학교", null), new DepartmentInfo(301L, "컴퓨터공학부"),
                new ClickerInfo(1L, 10, 300L), 0, 0);
    }

    // ===== 비밀번호 변경 (SCRUM-78) =====

    @Test
    @DisplayName("[PATCH] /api/v1/users/me/password는 비밀번호를 바꾸고 204와 Max-Age=0 refresh 쿠키로 응답")
    void changePassword() throws Exception {
        changePassword(passwordBody("Passw0rd!", "N3wPassw0rd!"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", allOf(
                        startsWith("refreshToken=;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=0"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"))));

        verify(userService).changePassword(1024L, "Passw0rd!", "N3wPassw0rd!");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPasswordChanges")
    @DisplayName("[PATCH] /api/v1/users/me/password는 입력 규칙을 어기면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void changePassword_invalidInput(String reason, String body, String field) throws Exception {
        changePassword(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value(field));

        verify(userService, never()).changePassword(any(), any(), any());
    }

    static Stream<Arguments> invalidPasswordChanges() {
        return Stream.of(
                Arguments.of("새 비밀번호 7자", passwordBody("Passw0rd!", "Pass0r!"), "newPassword"),
                Arguments.of("새 비밀번호 65자", passwordBody("Passw0rd!", "Passw0rd!" + "a".repeat(56)), "newPassword"),
                Arguments.of("새 비밀번호 특수문자 없음", passwordBody("Passw0rd!", "Passw0rdd"), "newPassword"),
                Arguments.of("새 비밀번호 공백 포함", passwordBody("Passw0rd!", "Pass w0rd!"), "newPassword"),
                Arguments.of("새 비밀번호 없음", "{\"currentPassword\":\"Passw0rd!\"}", "newPassword"),
                Arguments.of("현재 비밀번호 없음", "{\"newPassword\":\"N3wPassw0rd!\"}", "currentPassword"),
                Arguments.of("현재 비밀번호 65자", passwordBody("a".repeat(65), "N3wPassw0rd!"), "currentPassword"));
    }

    @Test
    @DisplayName("[PATCH] /api/v1/users/me/password 검증 실패 로그에 비밀번호 원문이 남지 않는다")
    void changePassword_invalidPasswordIsNotLogged(CapturedOutput output) throws Exception {
        changePassword(passwordBody("MyCurrentPw1!", "MySecretPw"))
                .andExpect(status().isBadRequest());

        assertThat(output).contains("newPassword").doesNotContain("MySecretPw").doesNotContain("MyCurrentPw1!");
    }

    @Test
    @DisplayName("[PATCH] /api/v1/users/me/password는 현재 비밀번호가 다르면 400 USER_INVALID_PASSWORD이고 쿠키를 지우지 않음")
    void changePassword_wrongCurrentPassword() throws Exception {
        willThrow(new BusinessException(ErrorCode.USER_INVALID_PASSWORD))
                .given(userService).changePassword(1024L, "Wr0ngPass!", "N3wPassw0rd!");

        changePassword(passwordBody("Wr0ngPass!", "N3wPassw0rd!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_INVALID_PASSWORD"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("[PATCH] /api/v1/users/me/password는 access 토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void changePassword_requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("Passw0rd!", "N3wPassw0rd!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verify(userService, never()).changePassword(any(), any(), any());
    }

    // ===== 회원 탈퇴 (SCRUM-79) =====

    @Test
    @DisplayName("[POST] /api/v1/users/me/withdrawal은 탈퇴 처리 후 204와 Max-Age=0 refresh 쿠키로 응답")
    void withdraw() throws Exception {
        withdraw(withdrawalBody("Passw0rd!"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", allOf(
                        startsWith("refreshToken=;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=0"))));

        verify(userService).withdraw(1024L, "Passw0rd!");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWithdrawals")
    @DisplayName("[POST] /api/v1/users/me/withdrawal은 비밀번호가 없거나 65자 이상이면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void withdraw_invalidInput(String reason, String body) throws Exception {
        withdraw(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("password"));

        verify(userService, never()).withdraw(any(), any());
    }

    static Stream<Arguments> invalidWithdrawals() {
        return Stream.of(
                Arguments.of("비밀번호 없음", "{}"),
                Arguments.of("비밀번호 빈 값", withdrawalBody("")),
                Arguments.of("비밀번호 65자", withdrawalBody("MyWithdrawPw" + "a".repeat(53))));
    }

    @Test
    @DisplayName("[POST] /api/v1/users/me/withdrawal 검증 실패 로그에 비밀번호 원문이 남지 않는다")
    void withdraw_invalidPasswordIsNotLogged(CapturedOutput output) throws Exception {
        withdraw(withdrawalBody("MyWithdrawPw" + "a".repeat(53))).andExpect(status().isBadRequest());

        assertThat(output).contains("password").doesNotContain("MyWithdrawPw");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withdrawalErrors")
    @DisplayName("[POST] /api/v1/users/me/withdrawal의 도메인 오류는 명세서의 상태와 코드로 응답하고 쿠키를 지우지 않음")
    void withdraw_businessErrors(ErrorCode errorCode, int httpStatus) throws Exception {
        willThrow(new BusinessException(errorCode)).given(userService).withdraw(1024L, "Passw0rd!");

        withdraw(withdrawalBody("Passw0rd!"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    static Stream<Arguments> withdrawalErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.USER_INVALID_PASSWORD, 400),
                Arguments.of(ErrorCode.USER_SUSPENDED, 403));
    }

    @Test
    @DisplayName("[POST] /api/v1/users/me/withdrawal은 access 토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void withdraw_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawalBody("Passw0rd!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verify(userService, never()).withdraw(any(), any());
    }

    private ResultActions withdraw(String body) throws Exception {
        String accessToken = jwtProvider.createAccessToken(new AuthUser(1024L, 12L, 34L, Role.USER), null);
        return mockMvc.perform(post("/api/v1/users/me/withdrawal")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String withdrawalBody(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private ResultActions changePassword(String body) throws Exception {
        String accessToken = jwtProvider.createAccessToken(new AuthUser(1024L, 12L, 34L, Role.USER), null);
        return mockMvc.perform(patch("/api/v1/users/me/password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String passwordBody(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }
}
