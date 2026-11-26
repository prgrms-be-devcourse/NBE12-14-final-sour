package com.back.nbe1214finalsour.domain.guestbook.controller.docs;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookLikeResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;

/** {@link com.back.nbe1214finalsour.domain.guestbook.controller.GuestBookLikeController}의 API 문서. */
@Tag(name = "방명록", description = "학교 방명록 조회·작성 및 좋아요 등록·취소")
public interface GuestBookLikeApiDocs {

    @Operation(
            summary = "방명록 좋아요 등록",
            description = """
                    방명록에 좋아요를 등록한다. 활성 회원의 로그인이 필요하다.
                    - 이미 좋아요를 등록한 경우 409를 반환한다.
                    - 성공하면 liked는 true이며 변경된 likeCount를 반환한다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = GuestBookErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = GuestBookErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = GuestBookErrorDocs.SUSPENDED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = GuestBookErrorDocs.USER_NOT_FOUND + " / " + GuestBookErrorDocs.GUEST_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = GuestBookErrorDocs.ALREADY_LIKED)
    })
    ApiResponse<GuestBookLikeResponse> like(
            @Parameter(description = "방명록 ID", example = "1") @Positive Long guestbookId,
            @Parameter(hidden = true) AuthUser authUser
    );

    @Operation(
            summary = "방명록 좋아요 취소",
            description = """
                    본인이 등록한 좋아요를 취소한다. 활성 회원의 로그인이 필요하다.
                    - 좋아요 기록이 없으면 404를 반환한다.
                    - 성공하면 liked는 false이며 변경된 likeCount를 반환한다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = GuestBookErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = GuestBookErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = GuestBookErrorDocs.SUSPENDED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = GuestBookErrorDocs.USER_NOT_FOUND + " / " + GuestBookErrorDocs.GUEST_NOT_FOUND + " / " + GuestBookErrorDocs.LIKE_NOT_FOUND)
    })
    ApiResponse<GuestBookLikeResponse> unlike(
            @Parameter(description = "방명록 ID", example = "1") @Positive Long guestbookId,
            @Parameter(hidden = true) AuthUser authUser
    );
}
