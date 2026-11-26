package com.back.nbe1214finalsour.domain.guestbook.controller.docs;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookCreateRequest;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** {@link com.back.nbe1214finalsour.domain.guestbook.controller.GuestBookController}의 API 문서. */
@Tag(name = "방명록", description = "학교 방명록 조회·작성 및 좋아요 등록·취소")
public interface GuestBookApiDocs {

    @Operation(
            summary = "방명록 목록 조회",
            description = """
                    학교 방명록을 ID 내림차순으로 조회한다. 로그인 없이 조회할 수 있다.

                    - 첫 요청은 cursor를 생략한다. 다음 요청은 응답의 nextCursor를 전달한다.
                    - size는 1~100이며 기본값은 20이다.
                    - 마지막 페이지는 hasNext가 false이고 nextCursor는 null이다.
                    - 비로그인 사용자의 likedByMe는 false이다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = GuestBookErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = GuestBookErrorDocs.UNIVERSITY_NOT_FOUND)
    })
    ApiResponse<GuestBookListResponse> getGuestBooks(
            @Parameter(description = "대학교 ID", example = "1") @Positive Long universityId,
            @Parameter(description = "이전 페이지의 nextCursor", example = "100") @Positive Long cursor,
            @Parameter(description = "조회 개수", example = "20") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) AuthUser authUser
    );

    @Operation(
            summary = "방명록 작성",
            description = """
                    다른 학교에 방명록을 작성한다. 활성 회원의 로그인이 필요하다.

                    - 본문은 앞뒤 공백 제거 후 유니코드 코드 포인트 기준 1~500자이다.
                    - 금칙어는 ***로 마스킹하며 마스킹 후 500자를 초과하면 400을 반환한다.
                    - 회원당 1분에 1개만 작성할 수 있다.
                    - 자기 학교에는 작성할 수 없다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = GuestBookErrorDocs.CREATE_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = GuestBookErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = GuestBookErrorDocs.SUSPENDED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = GuestBookErrorDocs.UNIVERSITY_NOT_FOUND + " / " + GuestBookErrorDocs.USER_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = GuestBookErrorDocs.TOO_FREQUENT)
    })
    ApiResponse<GuestBookListResponse.Item> createGuestBook(
            @Parameter(description = "대학교 ID", example = "1") @Positive Long universityId,
            GuestBookCreateRequest request,
            @Parameter(hidden = true) AuthUser authUser
    );
}
