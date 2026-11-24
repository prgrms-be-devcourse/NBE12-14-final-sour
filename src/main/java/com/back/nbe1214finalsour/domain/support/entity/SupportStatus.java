package com.back.nbe1214finalsour.domain.support.entity;

/** 문의 처리 상태. 값은 DB 제약 {@code ck_customer_support_status}와 같아야 한다 ({@code SupportStatusTest}가 검사). */
public enum SupportStatus {
    /** 접수: 아직 답변하지 않았다 */
    RECEIVED,
    /** 답변 완료: 답변 제목·내용이 반드시 있다 ({@code ck_customer_support_answered}) */
    ANSWERED
}
