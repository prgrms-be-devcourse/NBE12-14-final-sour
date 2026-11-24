package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.image.ImageStorage;
import com.back.nbe1214finalsour.domain.item.image.ImageType;
import com.back.nbe1214finalsour.domain.item.image.LogoImageValidator;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
public class ItemPurchaseServiceTest {

    @Mock
    private ItemService itemService;

    @Mock
    private CreditPurchaseService creditPurchaseService;

    @Mock
    private ContributionPurchaseService contributionPurchaseService;

    @Mock
    private LogoImageValidator logoImageValidator;

    @Mock
    private ImageStorage imageStorage;

    @InjectMocks
    private ItemPurchaseService itemPurchaseService;

    private static final Long USER_ID = 1L;
    private static final Long UNIVERSITY_ID = 12L;
    private static final Long DEPARTMENT_ID = 34L;
    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String LOGO_URL = "https://cdn.devcproject.cloud/custom-logo/1/abc.png";
    // 검증기는 Mock이라 내용은 상관없음
    private static final MockMultipartFile LOGO_IMAGE = new MockMultipartFile("logoImage", "logo.png", "image/png", new byte[]{1, 2, 3});

    private static final List<Item> ITEMS = List.of(
            new Item(1L, "2배 물약", "1시간 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 500L, 3600, 2.0),
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600, 4.0),
            new Item(3L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null),
            new Item(4L, "학과 로고 변경권", "우리 학과 로고 변경", ItemType.LOGO_DEPARTMENT, PayType.CREDIT, 2900L, null, null),
            new Item(5L, "개인 로고 변경권", "내 프로필 로고 변경", ItemType.LOGO_PROFILE, PayType.CREDIT, 1900L, null, null)
    );

    // 검증 통과(PNG) + 업로드 성공 시 LOGO_URL을 돌려줌
    private void givenLogoUploaded() {
        given(this.logoImageValidator.validate(LOGO_IMAGE)).willReturn(ImageType.PNG);
        given(this.imageStorage.upload(anyString(), eq(LOGO_IMAGE), eq(ImageType.PNG))).willReturn(LOGO_URL);
    }

    // 업로드할 때 쓴 파일 경로
    private String uploadedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(this.imageStorage).upload(key.capture(), any(), any());
        return key.getValue();
    }

    @Test
    @DisplayName("존재하지 않는 아이템을 사면 ITEM_NOT_FOUND, 검증·업로드·결제 모두 하지 않는다")
    void purchase_itemNotFound() {
        given(this.itemService.getItems()).willReturn(ITEMS);

        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, 3980L, LOGO_IMAGE, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_FOUND);

        verifyNoInteractions(this.logoImageValidator, this.imageStorage, this.creditPurchaseService, this.contributionPurchaseService);
    }

    @Test
    @DisplayName("기여도 아이템이면 ContributionPurchaseService에 위임하고, 이미지 검증·업로드·크레딧 구매는 하지 않는다")
    void purchase_contributionItem_delegatesWithoutUpload() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        ItemPurchaseResult expected = new ItemPurchaseResult(5530L, ITEMS.get(1), 1000L, null, 1500L, 1100L);
        given(this.contributionPurchaseService.purchase(USER_ID, UNIVERSITY_ID, ITEMS.get(1), KEY)).willReturn(expected);

        ItemPurchaseResult result = this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, null, 2L, null, KEY);

        assertThat(result).isSameAs(expected);
        then(this.contributionPurchaseService).should().purchase(USER_ID, UNIVERSITY_ID, ITEMS.get(1), KEY);
        verifyNoInteractions(this.logoImageValidator, this.imageStorage, this.creditPurchaseService);
    }

    @Test
    @DisplayName("로고 아이템이면 이미지를 custom-logo/{회원}/{UUID}.{확장자}로 올리고, 받은 주소로 크레딧 구매한다")
    void purchase_creditItem_uploadsThenPurchasesWithUploadedUrl() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        givenLogoUploaded();
        ItemPurchaseResult expected = new ItemPurchaseResult(7001L, ITEMS.get(2), 3900L, null, 12888L, 1100L);
        given(this.creditPurchaseService.purchase(eq(USER_ID), eq(UNIVERSITY_ID), eq(DEPARTMENT_ID), eq(ITEMS.get(2)), eq(LOGO_URL), any())).willReturn(expected);

        ItemPurchaseResult result = this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, UUID.randomUUID());

        assertThat(result).isSameAs(expected);
        assertThat(uploadedKey()).matches("custom-logo/1/[0-9a-f-]{36}\\.png");
        verify(this.imageStorage, never()).delete(anyString());
        verifyNoInteractions(this.contributionPurchaseService);
    }

    @Test
    @DisplayName("로고 아이템을 사면 요청의 멱등 키를 크레딧 구매에 그대로 넘긴다")
    void purchase_creditItem_passesIdempotencyKey() {
        // given
        given(this.itemService.getItems()).willReturn(ITEMS);
        givenLogoUploaded();
        ItemPurchaseResult expected = new ItemPurchaseResult(7001L, ITEMS.get(2), 3900L, null, 12888L, 1100L);
        given(this.creditPurchaseService.purchase(eq(USER_ID), eq(UNIVERSITY_ID), eq(DEPARTMENT_ID), eq(ITEMS.get(2)), eq(LOGO_URL), any())).willReturn(expected);

        // when
        ItemPurchaseResult result = this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, KEY);

        // then
        assertThat(result).isSameAs(expected);
        then(this.creditPurchaseService).should().purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, ITEMS.get(2), LOGO_URL, KEY);
    }

    @Test
    @DisplayName("이미지 검증에 실패하면 그 예외를 그대로 던지고, 업로드·크레딧 구매는 하지 않는다")
    void purchase_creditItem_invalidImage() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        given(this.logoImageValidator.validate(LOGO_IMAGE))
                .willThrow(new BusinessException(ErrorCode.ITEM_LOGO_IMAGE_INVALID_TYPE));

        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_LOGO_IMAGE_INVALID_TYPE);

        verifyNoInteractions(this.imageStorage, this.creditPurchaseService);
    }

    @Test
    @DisplayName("업로드에 실패하면 크레딧 구매를 하지 않는다 (크레딧이 빠지지 않음)")
    void purchase_creditItem_uploadFails() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        given(this.logoImageValidator.validate(LOGO_IMAGE)).willReturn(ImageType.PNG);
        RuntimeException s3Failure = new RuntimeException("S3 장애");
        given(this.imageStorage.upload(anyString(), eq(LOGO_IMAGE), eq(ImageType.PNG))).willThrow(s3Failure);

        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, UUID.randomUUID()))
                .isSameAs(s3Failure);

        verifyNoInteractions(this.creditPurchaseService);
        verify(this.imageStorage, never()).delete(anyString());
    }

    @Test
    @DisplayName("크레딧 구매가 실패하면 올린 이미지를 지우고, 구매 실패 예외를 그대로 던진다")
    void purchase_creditItem_purchaseFails_deletesUploadedImage() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        givenLogoUploaded();
        given(this.creditPurchaseService.purchase(eq(USER_ID), eq(UNIVERSITY_ID), eq(DEPARTMENT_ID), eq(ITEMS.get(2)), eq(LOGO_URL), any()))
                .willThrow(new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_CREDIT));

        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_CREDIT);

        // 올린 경로와 같은 경로를 지움 (verify 안에서 또 verify를 부르면 안 되므로 먼저 꺼냄)
        String key = uploadedKey();
        verify(this.imageStorage).delete(key);
    }

    @Test
    @DisplayName("같은 멱등 키로 제약 위반이 나면 올린 이미지를 지우고, 그 예외를 그대로 던진다")
    void purchase_creditItem_duplicateKey_deletesUploadedImageAndRethrows() {
        // given
        given(this.itemService.getItems()).willReturn(ITEMS);
        givenLogoUploaded();
        DataIntegrityViolationException violation = new DataIntegrityViolationException("uk_credit_history_user_idempotency_key");
        given(this.creditPurchaseService.purchase(eq(USER_ID), eq(UNIVERSITY_ID), eq(DEPARTMENT_ID), eq(ITEMS.get(2)), eq(LOGO_URL), eq(KEY)))
                .willThrow(violation);

        // when & then
        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, KEY))
                .isSameAs(violation);

        String key = uploadedKey();
        then(this.imageStorage).should().delete(key);
    }

    @Test
    @DisplayName("구매 실패 후 이미지 삭제까지 실패해도, 던지는 예외는 원래 구매 실패 예외다")
    void purchase_creditItem_deleteAlsoFails_keepsPurchaseFailure() {
        given(this.itemService.getItems()).willReturn(ITEMS);
        givenLogoUploaded();
        given(this.creditPurchaseService.purchase(eq(USER_ID), eq(UNIVERSITY_ID), eq(DEPARTMENT_ID), eq(ITEMS.get(2)), eq(LOGO_URL), any()))
                .willThrow(new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_CREDIT));
        willThrow(new RuntimeException("S3 삭제 실패")).given(this.imageStorage).delete(anyString());

        assertThatThrownBy(() -> this.itemPurchaseService.purchase(USER_ID, UNIVERSITY_ID, DEPARTMENT_ID, 3L, LOGO_IMAGE, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_CREDIT);
                    // 삭제 실패는 덮어쓰지 않고 suppressed로 붙음
                    assertThat(e.getSuppressed()).hasSize(1);
                });
    }
}