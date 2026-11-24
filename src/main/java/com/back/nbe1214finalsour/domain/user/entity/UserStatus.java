package com.back.nbe1214finalsour.domain.user.entity;

/** 회원 상태. 값은 DB 제약 {@code ck_users_status}와 같아야 한다 ({@code UserStatusTest}가 검사). */
public enum UserStatus {
    ACTIVE,
    /** 정지: 기간이 정해진 이용 제한 */
    SUSPENDED,
    /** 차단: 영구 이용 제한 */
    BANNED,
    /** 탈퇴: {@code deleted_at}이 함께 채워진다 ({@code ck_users_withdrawn}) */
    WITHDRAWN
}
