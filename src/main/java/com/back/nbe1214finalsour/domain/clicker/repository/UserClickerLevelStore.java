package com.back.nbe1214finalsour.domain.clicker.repository;

public interface UserClickerLevelStore {

    // 유저의 현재 클리커 레벨
    long findLevel(Long userId);
}
