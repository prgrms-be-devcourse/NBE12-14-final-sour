package com.back.nbe1214finalsour.domain.university.controller.docs;

/** 대학교 API가 내는 에러 설명. */
final class UniversityErrorDocs {

    static final String SEARCH_INVALID_INPUT =
            "`COMMON_INVALID_INPUT` keyword가 없거나 공백 / size가 정수가 아니거나 1~100 밖";

    static final String INVALID_UNIVERSITY_ID =
            "`COMMON_INVALID_INPUT` universityId가 Long 형식이 아님";

    static final String UNIVERSITY_NOT_FOUND = "`UNIV_NOT_FOUND` 없는 학교";

    private UniversityErrorDocs() {
    }
}
