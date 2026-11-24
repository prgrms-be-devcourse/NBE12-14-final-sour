package com.back.nbe1214finalsour.domain.item.controller;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.service.*;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.*;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ItemController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
public class ItemControllerTest {
    // 토큰의 회원: userId 1, universityId 7, departmentId 34
    private static final AuthUser USER = new AuthUser(1L, 7L, 34L, Role.USER);

    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private static final Item POTION =
            new Item(2L, "4배 물약", "1시간 동안 클릭 점수 4배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 1000L, 3600, 4.0);

    private static final Item UNIVERSITY_LOGO =
            new Item(3L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null);

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2};

    private static MockMultipartFile logoImage() {
        return new MockMultipartFile("logoImage", "logo.png", "image/png", PNG_BYTES);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    // SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private ItemService itemService;

    @MockitoBean
    private ItemPurchaseService itemPurchaseService;

    @MockitoBean
    private PurchaseHistoryService purchaseHistoryService;

    @MockitoBean
    private ActiveEffectService activeEffectService;

    private String bearerToken() {
        return "Bearer " + jwtProvider.createAccessToken(USER, null);
    }

    @Test
    @DisplayName("[GET] /api/v1/items는 로그인 없이 아이템 목록을 200으로 응답")
    void getItems() throws Exception {
        // given
        given(itemService.getItems()).willReturn(List.of(
                new Item(1L, "2배 클릭", "10분 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0),
                new Item(2L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null)
        ));

        // when & then: 토큰 없이 요청
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("2배 클릭"))
                .andExpect(jsonPath("$.data[0].content").value("10분 동안 클릭 점수 2배"))
                .andExpect(jsonPath("$.data[0].type").value("MULTIPLIER"))
                .andExpect(jsonPath("$.data[0].payType").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.data[0].price").value(3000))
                .andExpect(jsonPath("$.data[0].durationSeconds").value(600))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].type").value("LOGO_UNIVERSITY"))
                .andExpect(jsonPath("$.data[1].payType").value("CREDIT"))
                .andExpect(jsonPath("$.data[1].durationSeconds").value(nullValue()));
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 토큰이 없으면 401")
    void purchase_requiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/items/2/purchase"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 Idempotency-Key 헤더가 없으면 400 COMMON_INVALID_INPUT")
    void purchase_missingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/items/2/purchase")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 Idempotency-Key가 UUID 형식이 아니면 400 COMMON_INVALID_INPUT")
    void purchase_invalidIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/items/2/purchase")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 토큰의 회원·학교로 구매하고 201과 구매 결과를 응답")
    void purchase() throws Exception {
        // given: 1시간 뒤 만료(UTC 04:00 = KST 13:00), 차감 후 기여도 1500, 크레딧 1100
        OffsetDateTime expiredAt = OffsetDateTime.of(2026, 11, 14, 4, 0, 0, 0, ZoneOffset.UTC);
        given(itemPurchaseService.purchase(1L, 7L, 34L, 2L, null, KEY))
                .willReturn(new ItemPurchaseResult(5530L, POTION, 1000L, expiredAt, 1500L, 1100L));

        // when & then
        mockMvc.perform(post("/api/v1/items/2/purchase")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userItemId").value(5530))
                .andExpect(jsonPath("$.data.item.id").value(2))
                .andExpect(jsonPath("$.data.item.name").value("4배 물약"))
                .andExpect(jsonPath("$.data.item.payType").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.data.price").value(1000))
                // API 명세 §1: 시각은 KST 오프셋으로 응답 (application.yaml의 jackson.time-zone)
                .andExpect(jsonPath("$.data.expiredAt").value("2026-11-14T13:00:00+09:00"))
                .andExpect(jsonPath("$.data.myContribScore").value(1500))
                .andExpect(jsonPath("$.data.myCreditBalance").value(1100));

        // 헤더 값이 그대로 서비스에 전달됨
        then(itemPurchaseService).should().purchase(1L, 7L, 34L, 2L, null, KEY);
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 없는 아이템이면 404 ITEM_NOT_FOUND")
    void purchase_itemNotFound() throws Exception {
        given(itemPurchaseService.purchase(1L, 7L, 34L, 9999L, null, KEY))
                .willThrow(new BusinessException(ErrorCode.ITEM_NOT_FOUND));

        mockMvc.perform(post("/api/v1/items/9999/purchase")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 기여도가 부족하면 400 ITEM_NOT_ENOUGH_SCORE")
    void purchase_notEnoughScore() throws Exception {
        given(itemPurchaseService.purchase(1L, 7L, 34L, 2L, null, KEY))
                .willThrow(new BusinessException(ErrorCode.ITEM_NOT_ENOUGH_SCORE));

        mockMvc.perform(post("/api/v1/items/2/purchase")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_ENOUGH_SCORE"));
    }



    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 로고 아이템이면 multipart의 logoImage 파트로 이미지를 받아 구매하고 201을 응답")
    void purchase_logoItem_multipart() throws Exception {
        given(itemPurchaseService.purchase(eq(1L), eq(7L), eq(34L), eq(3L), any(MultipartFile.class), eq(KEY)))
                .willReturn(new ItemPurchaseResult(7001L, UNIVERSITY_LOGO, 3900L, null, 12888L, 1100L));

        mockMvc.perform(multipart("/api/v1/items/3/purchase")
                        .file(logoImage())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userItemId").value(7001))
                .andExpect(jsonPath("$.data.item.payType").value("CREDIT"))
                .andExpect(jsonPath("$.data.expiredAt").value(nullValue()))
                .andExpect(jsonPath("$.data.myCreditBalance").value(1100))
                .andExpect(jsonPath("$.data.myContribScore").value(12888));

        // 보낸 파일이 그대로 서비스에 전달됨
        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(itemPurchaseService).purchase(eq(1L), eq(7L), eq(34L), eq(3L), file.capture(), eq(KEY));
        assertThat(file.getValue().getOriginalFilename()).isEqualTo("logo.png");
        assertThat(file.getValue().getBytes()).isEqualTo(PNG_BYTES);
    }

    @Test
    @DisplayName("[POST] /api/v1/items/{itemId}/purchase는 업로드 크기 제한을 넘으면 400 ITEM_LOGO_IMAGE_TOO_LARGE")
    void purchase_uploadTooLarge() throws Exception {
        // 실제로는 서블릿이 요청 본문을 읽다가 던지는 예외. MockMvc는 크기 제한을 적용하지 않으므로 서비스가 던지게 해서 응답 형식만 확인한다
        given(itemPurchaseService.purchase(eq(1L), eq(7L), eq(34L), eq(3L), any(MultipartFile.class), eq(KEY)))
                .willThrow(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        mockMvc.perform(multipart("/api/v1/items/3/purchase")
                        .file(logoImage())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_LOGO_IMAGE_TOO_LARGE"));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/active는 토큰이 없으면 401")
    void getActiveEffect_requiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/me/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/active는 토큰의 회원에게 적용 중인 효과를 200으로 응답")
    void getActiveEffect() throws Exception {
        // given: UTC 03:06:52(= KST 12:06:52)에 만료, 412초 남음
        OffsetDateTime expiredAt = OffsetDateTime.of(2026, 11, 17, 3, 6, 52, 0, ZoneOffset.UTC);
        given(activeEffectService.findActive(1L))
                .willReturn(Optional.of(new ActiveEffect(2L, "4배 물약", 4.0, expiredAt, 412L)));

        // when & then
        mockMvc.perform(get("/api/v1/items/me/active")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.itemId").value(2))
                .andExpect(jsonPath("$.data.name").value("4배 물약"))
                .andExpect(jsonPath("$.data.multiplier").value(4.0))
                // API 명세 1: 시각은 KST 오프셋으로 응답
                .andExpect(jsonPath("$.data.expiredAt").value("2026-11-17T12:06:52+09:00"))
                .andExpect(jsonPath("$.data.remainingSeconds").value(412));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/active는 적용 중인 효과가 없으면 data를 null로 200 응답")
    void getActiveEffect_none() throws Exception {
        given(activeEffectService.findActive(1L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/items/me/active")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }



    @Test
    @DisplayName("[GET] /api/v1/items/me/history는 토큰이 없으면 401")
    void getPurchaseHistory_requiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/items/me/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/history는 토큰의 회원 구매 내역을 기본 0페이지·20개로 200 응답")
    void getPurchaseHistory() throws Exception {
        // given: 로고(UTC 05:00 = KST 14:00 구매, 영구) → 물약(UTC 03:00 = KST 12:00 구매, 1시간 효과) 순서
        OffsetDateTime logoAt = OffsetDateTime.of(2026, 11, 17, 5, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime potionAt = OffsetDateTime.of(2026, 11, 17, 3, 0, 0, 0, ZoneOffset.UTC);
        given(purchaseHistoryService.findHistory(1L, 0, 20)).willReturn(new PageImpl<>(List.of(
                new PurchaseHistory(7001L, UNIVERSITY_LOGO, 3900L, logoAt, null),
                new PurchaseHistory(5530L, POTION, 1000L, potionAt, potionAt.plusHours(1))
        ), PageRequest.of(0, 20), 2));

        // when & then: page·size 없이 요청
        mockMvc.perform(get("/api/v1/items/me/history")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].userItemId").value(7001))
                .andExpect(jsonPath("$.data.items[0].item.id").value(3))
                .andExpect(jsonPath("$.data.items[0].item.name").value("학교 로고 변경권"))
                .andExpect(jsonPath("$.data.items[0].item.payType").value("CREDIT"))
                .andExpect(jsonPath("$.data.items[0].price").value(3900))
                // API 명세 1: 시각은 KST 오프셋으로 응답
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-17T14:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].expiredAt").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].userItemId").value(5530))
                .andExpect(jsonPath("$.data.items[1].item.payType").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.data.items[1].price").value(1000))
                .andExpect(jsonPath("$.data.items[1].createdAt").value("2026-11-17T12:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[1].expiredAt").value("2026-11-17T13:00:00+09:00"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/history는 요청한 page·size로 조회하고 페이지 정보를 응답")
    void getPurchaseHistory_paging() throws Exception {
        // given: 전체 7건을 5개씩 나눈 2페이지(0부터 셈)라 2건이 있고, 마지막 페이지
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 17, 3, 0, 0, 0, ZoneOffset.UTC);
        given(purchaseHistoryService.findHistory(1L, 1, 5)).willReturn(new PageImpl<>(List.of(
                new PurchaseHistory(5531L, POTION, 1000L, at.plusHours(1), at.plusHours(2)),
                new PurchaseHistory(5530L, POTION, 1000L, at, at.plusHours(1))
        ), PageRequest.of(1, 5), 7));

        // when & then
        mockMvc.perform(get("/api/v1/items/me/history")
                        .param("page", "1")
                        .param("size", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.totalCount").value(7))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("[GET] /api/v1/items/me/history는 아이템 정보가 없는 구매면 item을 null로 응답")
    void getPurchaseHistory_unknownItem() throws Exception {
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 17, 3, 0, 0, 0, ZoneOffset.UTC);
        given(purchaseHistoryService.findHistory(1L, 0, 20)).willReturn(new PageImpl<>(List.of(
                new PurchaseHistory(7001L, null, 3900L, at, null)
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/items/me/history")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userItemId").value(7001))
                .andExpect(jsonPath("$.data.items[0].item").value(nullValue()));
    }

    @ParameterizedTest(name = "page={0}, size={1}이면 {2} 검증 실패")
    @CsvSource({"0, 0, size", "0, 101, size", "-1, 20, page"})
    @DisplayName("[GET] /api/v1/items/me/history는 page가 음수이거나 size가 1~100 밖이면 400 COMMON_INVALID_INPUT")
    void getPurchaseHistory_invalidPaging(String page, String size, String field) throws Exception {
        mockMvc.perform(get("/api/v1/items/me/history")
                        .param("page", page)
                        .param("size", size)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value(field));
    }

}
