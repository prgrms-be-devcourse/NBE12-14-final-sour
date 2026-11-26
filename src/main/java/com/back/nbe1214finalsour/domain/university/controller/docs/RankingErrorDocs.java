package com.back.nbe1214finalsour.domain.university.controller.docs;

/** 랭킹 및 지도 API의 에러 설명. */
final class RankingErrorDocs {

    static final String INVALID_PAGE = "`COMMON_INVALID_INPUT` page가 0보다 작거나 size가 1~100 밖 또는 요청 형식 오류";
    static final String INVALID_LIMIT = "`COMMON_INVALID_INPUT` limit이 1~300 밖 또는 요청 형식 오류";
    static final String UNIVERSITY_NOT_FOUND = "`UNIV_NOT_FOUND` 학교를 찾을 수 없음";
    static final String RANK_CHANGED = "`COMMON_INTERNAL_ERROR` 조회 중 Redis 랭킹 정보가 변경됨";

    private RankingErrorDocs() {
    }
}
