package com.back.nbe1214finalsour.global.security;

/** 회원 권한. 값은 DB 제약 {@code ck_users_role}과 같아야 한다 ({@code RoleTest}가 검사). */
public enum Role {
    USER,
    ADMIN
}
