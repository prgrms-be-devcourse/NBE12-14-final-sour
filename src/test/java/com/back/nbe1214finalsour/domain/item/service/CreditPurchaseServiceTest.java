package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.contribution.ContributionWallet;
import com.back.nbe1214finalsour.domain.credit.entity.CreditHistory;
import com.back.nbe1214finalsour.domain.credit.entity.CreditHistoryType;
import com.back.nbe1214finalsour.domain.credit.repository.CreditHistoryRepository;
import com.back.nbe1214finalsour.domain.credit.repository.CreditWallet;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CreditPurchaseServiceTest {

    @Mock
    private CreditWallet creditWallet;

    @Mock
    private ContributionWallet contributionWallet;

    @Mock
    private UserItemRepository userItemRepository;

    @Mock
    private CreditHistoryRepository creditHistoryRepository;

    @Mock
    private UniversityRepository universityRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CreditPurchaseService creditPurchaseService;

    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long DEPARTMENT_ID = 34L;
    private static final String LOGO_URL = "https://cdn.devcproject.cloud/custom-logo/1/abc.png";
    private static final String PROFILE_URL = "https://cdn.devcproject.cloud/profile/1/abc.png";

    private static final Item UNIVERSITY_LOGO =
            new Item(3L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null);
    private static final Item DEPARTMENT_LOGO =
            new Item(4L, "학과 로고 변경권", "우리 학과 로고 변경", ItemType.LOGO_DEPARTMENT, PayType.CREDIT, 2900L, null, null);
    private static final Item PROFILE_LOGO =
            new Item(5L, "개인 로고 변경권", "내 프로필 로고 변경", ItemType.LOGO_PROFILE, PayType.CREDIT, 1900L, null, null);

    // 토큰의 학교가 DB에 있음
    private University givenUniversity() {
        University university = new University("로고테스트대");
        given(this.universityRepository.findById(UNIVERSITY_ID)).willReturn(Optional.of(university));
        return university;
    }

    // IDENTITY 저장 시 DB가 같은 객체에 id를 채워 주는 것을 흉내
    private void givenUserItemSavedWithId(long id) {
        given(this.userItemRepository.save(any()))
                .willAnswer(invocation -> {
                    UserItem userItem = invocation.getArgument(0);
                    ReflectionTestUtils.setField(userItem, "id", id);
                    return userItem;
                });
    }

    @Test
    @DisplayName("크레딧 아이템을 사면 크레딧을 차감하고, 기여도는 차감하지 않는다")
    void purchase_deductsCredit() {
        given(this.creditWallet.tryDeduct(USER_ID, 3900L)).willReturn(OptionalLong.of(1100L));
        givenUniversity();

        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, LOGO_URL, KEY);

        verify(this.creditWallet).tryDeduct(USER_ID, 3900L);
    }

    @Test
    @DisplayName("로고 이미지 주소가 없으면 ITEM_LOGO_IMAGE_REQUIRED, 크레딧을 차감하지 않는다")
    void purchase_logoImageRequired() {
        assertThatThrownBy(() -> this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, null, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_REQUIRED);

        verify(this.creditWallet, never())
                .tryDeduct(anyLong(), anyLong());
    }

    @Test
    @DisplayName("크레딧이 부족하면 ITEM_NOT_ENOUGH_CREDIT, 구매 이력과 크레딧 사용 내역을 저장하지 않는다")
    void purchase_notEnoughCredit() {
        given(this.creditWallet.tryDeduct(USER_ID, 3900L))
                .willReturn(OptionalLong.empty());

        assertThatThrownBy(() -> this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, LOGO_URL, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_CREDIT);

        verify(this.userItemRepository, never())
                .save(any());
        verify(this.creditHistoryRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("로고 구매 이력은 가격 스냅샷과 로고 이미지를 담고, 영구라 만료 시각이 없다")
    void purchase_saveLogoUserItem() {
        given(this.creditWallet.tryDeduct(USER_ID, 3900L))
                .willReturn(OptionalLong.of(1100L));
        givenUniversity();

        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, LOGO_URL, KEY);

        ArgumentCaptor<UserItem> captor = ArgumentCaptor.forClass(UserItem.class);
        verify(this.userItemRepository).save(captor.capture());

        UserItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getItemId()).isEqualTo(3L);
        assertThat(saved.getPrice()).isEqualTo(3900L);
        assertThat(saved.getLogoImageUrl()).isEqualTo(LOGO_URL);
        assertThat(saved.getExpiredAt()).isNull();
    }

    @Test
    @DisplayName("크레딧 사용 내역은 방금 저장한 구매 이력과 연결되고, 사용 금액·차감 후 잔액·멱등 키를 담는다")
    void purchase_saveCreditHistory() {
        // given
        given(this.creditWallet.tryDeduct(USER_ID, 3900L))
                .willReturn(OptionalLong.of(1100L));
        givenUserItemSavedWithId(7001L);
        givenUniversity();

        // when
        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, LOGO_URL, KEY);

        // then
        ArgumentCaptor<CreditHistory> captor = ArgumentCaptor.forClass(CreditHistory.class);
        then(this.creditHistoryRepository).should().save(captor.capture());

        CreditHistory saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getUserItemId()).isEqualTo(7001L);
        assertThat(saved.getType()).isEqualTo(CreditHistoryType.USE);
        assertThat(saved.getAmount()).isEqualTo(3900L);
        assertThat(saved.getBalanceAfter()).isEqualTo(1100L);
        assertThat(saved.getIdempotencyKey()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("크레딧 구매 결과: 크레딧은 차감 후 값, 기여도는 차감되지 않은 현재 값, 만료 시각은 없음")
    void purchase_returnsResult() {
        given(this.creditWallet.tryDeduct(USER_ID, 3900L))
                .willReturn(OptionalLong.of(1100L));
        givenUserItemSavedWithId(7001L);
        given(this.contributionWallet.findBalance(USER_ID, UNIVERSITY_ID))
                .willReturn(12888L);
        givenUniversity();

        ItemPurchaseResult result = this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, UNIVERSITY_LOGO, LOGO_URL, KEY);

        assertThat(result.userItemId()).isEqualTo(7001L);
        assertThat(result.item()).isSameAs(UNIVERSITY_LOGO);
        assertThat(result.price()).isEqualTo(3900L);
        assertThat(result.expiredAt()).isNull();
        assertThat(result.myCreditBalance()).isEqualTo(1100L);
        assertThat(result.myContribScore()).isEqualTo(12888L);
    }

    @Test
    @DisplayName("학교 로고 변경권을 사면 토큰의 학교 로고를 새 이미지로 바꾼다")
    void purchase_universityLogo_changesUniversityLogo() {
        given(this.creditWallet.tryDeduct(USER_ID, 3900L)).willReturn(OptionalLong.of(1100L));
        University university = givenUniversity();

        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, UNIVERSITY_LOGO, LOGO_URL, KEY);

        assertThat(university.getLogoUrl()).isEqualTo(LOGO_URL);
    }

    @Test
    @DisplayName("학과 로고 변경권을 사면 토큰의 학과 로고를 새 이미지로 바꾼다")
    void purchase_departmentLogo_changesDepartmentLogo() {
        given(this.creditWallet.tryDeduct(USER_ID, 2900L)).willReturn(OptionalLong.of(2100L));
        Department department = new Department(new University("로고테스트대"), "컴퓨터공학과");
        given(this.departmentRepository.findById(DEPARTMENT_ID)).willReturn(Optional.of(department));

        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, DEPARTMENT_LOGO, LOGO_URL, KEY);

        assertThat(department.getLogoUrl()).isEqualTo(LOGO_URL);
    }

    @Test
    @DisplayName("개인 로고 변경권을 사면 내 프로필 로고를 새 이미지로 바꾼다")
    void purchase_profileLogo_changesProfileLogo() {
        given(this.creditWallet.tryDeduct(USER_ID, 1900L)).willReturn(OptionalLong.of(3100L));
        User user = User.create(UNIVERSITY_ID, DEPARTMENT_ID, "로고유저", "logo@test.com",
                "a".repeat(64), "encoded", OffsetDateTime.now());
        given(this.userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        this.creditPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, PROFILE_LOGO, PROFILE_URL, KEY);

        assertThat(user.getLogoUrl()).isEqualTo(PROFILE_URL);
    }
}