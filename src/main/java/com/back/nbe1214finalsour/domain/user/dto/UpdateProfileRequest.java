package com.back.nbe1214finalsour.domain.user.dto;

import com.back.nbe1214finalsour.domain.user.validation.Nickname;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code PATCH /api/v1/users/me} 요청 (추가 명세 §3-1). 빠지거나 {@code null}이면 그대로 둔다.
 *
 * <p>프로필 이미지는 여기서 바꾸지 않는다. 개인 로고 변경권({@code LOGO_PROFILE}) 구매로만 바꾼다 (SCRUM-105).
 * 예전 클라이언트가 {@code profileImageUrl}을 보내도 모르는 필드라 무시된다.
 *
 * @param nickname 규칙은 {@link Nickname}
 */
public record UpdateProfileRequest(
        @Schema(description = "변경할 닉네임", example = "학생")
        @Nickname String nickname) {
}
