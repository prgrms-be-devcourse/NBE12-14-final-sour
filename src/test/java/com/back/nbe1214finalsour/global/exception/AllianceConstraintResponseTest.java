package com.back.nbe1214finalsour.global.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AllianceConstraintResponseTest {

    @Test
    @DisplayName("학교 중복 가입 제약조건 위반 시 HTTP 409를 반환한다")
    void duplicateAllianceUniversity_returnsConflict()
            throws Exception {

        // given: 실제 공통 예외 처리기를 사용하는 테스트 환경
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ConflictTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // when & then
        mockMvc.perform(get("/test/alliance-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ALLY_UNIVERSITY_ALREADY_JOINED"));
    }

    // DB 중복 오류를 재현하기 위한 테스트 전용 Controller
    @RestController
    static class ConflictTestController {

        @GetMapping("/test/alliance-conflict")
        public void conflict() {

            // PostgreSQL UNIQUE 제약조건 위반 상황 재현
            SQLException sqlException = new SQLException(
                    "duplicate key value violates unique constraint",
                    "23505"
            );

            ConstraintViolationException hibernateException =
                    new ConstraintViolationException(
                            "연합 학교 중복 가입",
                            sqlException,
                            "INSERT INTO alliance_university",
                            "uk_alliance_university_one_accepted"
                    );

            throw new DataIntegrityViolationException(
                    "연합 학교 중복 가입",
                    hibernateException
            );
        }
    }
}
