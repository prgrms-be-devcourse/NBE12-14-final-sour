package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.contribution.ContributionWallet;
import com.back.nbe1214finalsour.domain.credit.entity.CreditHistory;
import com.back.nbe1214finalsour.domain.credit.repository.CreditHistoryRepository;
import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 크레딧 아이템(로고 변경권) 구매
// 디스패처(ItemPurchaseService)와 다른 빈이어야 프록시를 거쳐 @Transactional이 걸린다
@Service
@RequiredArgsConstructor
public class CreditPurchaseService {

    private final CreditWallet creditWallet;
    private final ContributionWallet contributionWallet;
    private final UserItemRepository userItemRepository;
    private final CreditHistoryRepository creditHistoryRepository;
    private final UniversityRepository universityRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    // DB 한 트랜잭션이라 중간에 실패하면 크레딧 차감까지 함께 롤백된다
    @Transactional
    public ItemPurchaseResult purchase(Long userId, Long universityId, Long departmentId, Item item, String logoImageUrl, UUID idempotencyKey) {
        if (logoImageUrl == null || logoImageUrl.isBlank()) {
            throw new BusinessException(ErrorCode.ITEM_LOGO_IMAGE_REQUIRED);
        }

        // 크레딧 차감 (조건부 UPDATE), 성공하면 차감 후 잔액
        long myCreditBalance = this.creditWallet.tryDeduct(userId, item.getPrice())
                .orElseThrow(() -> new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_CREDIT));

        // 구매 이력 저장 (IDENTITY라 저장 후 같은 객체에 id가 채워짐)
        UserItem userItem = UserItem.ofLogo(userId, item, logoImageUrl);
        this.userItemRepository.save(userItem);

        // 크레딧 사용 내역 저장 (구매 이력과 연결)
        this.creditHistoryRepository.save(
                CreditHistory.use(userId, userItem.getId(), item.getPrice(), myCreditBalance, idempotencyKey));

        // 로고 적용: 아이템 종류에 따라 학교·학과·개인 로고를 덮어씀 (마지막 구매 기준, 커밋 시 변경 감지로 UPDATE)
        switch (item.getType()) {
            case LOGO_UNIVERSITY -> this.universityRepository.findById(universityId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNIV_NOT_FOUND))
                    .changeLogoUrl(logoImageUrl);
            case LOGO_DEPARTMENT -> this.departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNIV_DEPARTMENT_NOT_FOUND))
                    .changeLogoUrl(logoImageUrl);
            case LOGO_PROFILE -> this.userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                    .changeProfileImageUrl(logoImageUrl);
            default -> throw new IllegalStateException("크레딧 아이템이 로고가 아님: " + item.getType());
        }

        // 크레딧 아이템이라 기여도는 차감되지 않은 현재 값
        return new ItemPurchaseResult(
                userItem.getId(),
                item,
                userItem.getPrice(),
                userItem.getExpiredAt(),
                this.contributionWallet.findBalance(userId, universityId),
                myCreditBalance
        );
    }
}