package com.back.nbe1214finalsour.domain.abuse.controller.docs;

import com.back.nbe1214finalsour.domain.abuse.dto.AbuseVerificationRequest;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

/** {@link com.back.nbe1214finalsour.domain.abuse.controller.AbuseVerificationController}의 API 문서. */
@Tag(name = "어뷰징 확인", description = "클릭 봇 의심 표시를 Turnstile 확인으로 해제")
public interface AbuseVerificationApiDocs {
    @Operation(summary = "클릭 봇 확인", description = """
            클릭 봇 의심 표시가 생겨 클릭이 제한되면 Turnstile 토큰으로 확인한다.
            통과하면 표시를 지우고, 표시가 없어도 성공한다. Cloudflare 연결 불가 시 통과시킨다(fail-open).
            Turnstile이 꺼져 있으면 확인과 한도 검사를 하지 않는다.
            확인 시도는 회원당 기본 10분에 10번까지다 (설정 가능).
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "확인 성공, 본문 없음",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_INPUT` 요청 본문 또는 토큰 길이 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 인증 필요 / `COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / "
                            + "`COMMON_EXPIRED_TOKEN` 만료된 토큰 / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`ABUSE_VERIFICATION_FAILED` Turnstile 확인 거절"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`COMMON_TOO_MANY_REQUESTS` 회원별 확인 시도 한도 초과")
    })
    ResponseEntity<Void> verify(AuthUser authUser, AbuseVerificationRequest request, HttpServletRequest servletRequest);
}
