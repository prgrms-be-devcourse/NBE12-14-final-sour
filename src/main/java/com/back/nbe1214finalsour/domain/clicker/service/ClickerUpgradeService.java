package com.back.nbe1214finalsour.domain.clicker.service;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.RedisClickerUpgrader;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClickerUpgradeService {

    private final ClickerService clickerService;
    private final UserClickerLevelCache userClickerLevelCache;
    private final RedisClickerUpgrader redisClickerUpgrader;

    public ClickerUpgradeResult upgrade(Long userId, Long universityId) {
        List<Clicker> levelTable = this.clickerService.getLevelTable();
        // Redis가 레벨 원본. 키가 없으면 DB 레벨로 채운다
        long currentLevel = this.userClickerLevelCache.loadIfAbsent(userId);

        // 유저의 현재 레벨+1의 레벨이 레벨표에 존재하는 지 확인 후 해당 클리커 반환
        Clicker nextClicker = levelTable.stream()
                .filter(clicker -> clicker.getId() == currentLevel + 1)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CLICKER_MAX_LEVEL)); // 없으면 이미 최고 레벨

        // 기대 레벨 비교·기여도 차감·레벨 +1·배치 표시를 Redis Lua 한 번으로 처리 (DB는 배치가 반영)
        // 반환: >=0 잔액, -1 기여도 부족, -2 레벨이 기대값과 다름(동시 요청)
        long upgradeResult = this.redisClickerUpgrader.upgrade(
                userId, universityId, currentLevel, nextClicker.getRequiredScore());
        if (upgradeResult == -1) {
            throw new BusinessException(ErrorCode.CLICKER_NOT_ENOUGH_SCORE);
        }
        if (upgradeResult == -2) {
            throw new BusinessException(ErrorCode.CLICKER_UPGRADE_CONFLICT);
        }
        long remainingScore = upgradeResult;

        // 다음 레벨업에 필요한 기여도 계산, 이번 레벨업 결과가 최고 레벨이라면 null
        Long nextLevelRequiredScore = levelTable.stream()
                .filter(clicker -> clicker.getId() == currentLevel + 2)
                .findFirst()
                .map(Clicker::getRequiredScore)
                .orElse(null);

        return new ClickerUpgradeResult(
                nextClicker,
                nextLevelRequiredScore,
                remainingScore
                );
    }
}
