package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.crypto.EmailCipher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 커밋·롤백을 실제로 확인해야 하므로 @Transactional(테스트 후 롤백)을 쓰지 않고, 넣은 데이터는 직접 지운다
// 로고 이미지는 Floci(S3 에뮬레이터)에 실제로 올라간다
@IntegrationTest
class ItemCreditPurchaseIntegrationTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2};
    private static final int THREAD_COUNT = 10;

    @Autowired
    private ItemPurchaseService itemPurchaseService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailCipher emailCipher;

    @Autowired
    private S3Template s3Template;

    @Value("${file.logo-bucket}")
    private String bucket;

    @Value("${file.logo-public-base-url}")
    private String publicBaseUrl;

    private Long universityId;
    private Long departmentId;
    private Long userId;
    private Long universityLogoItemId;
    private Long departmentLogoItemId;
    private Long profileLogoItemId;
    private final List<Long> insertedItemIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('크레딧구매테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '크레딧구매유저', ?, ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId,
                // 개인 로고 변경권은 User 엔티티를 읽으므로 이메일이 복호화돼야 한다
                emailCipher.encrypt("credit-purchase@test.com"), "3".repeat(64));

        universityLogoItemId = logoItem("LOGO_UNIVERSITY", 3900);
        departmentLogoItemId = logoItem("LOGO_DEPARTMENT", 2900);
        profileLogoItemId = logoItem("LOGO_PROFILE", 1900);
        itemService.loadItemTable();   // 서버 시작 때 캐시된 아이템 목록을 다시 읽음
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: credit_history → user_item → item·users → department → university
        jdbcTemplate.update("DELETE FROM credit_history WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_item WHERE user_id = ?", userId);
        insertedItemIds.forEach(id -> jdbcTemplate.update("DELETE FROM item WHERE id = ?", id));
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        itemService.loadItemTable();   // 아이템 목록 캐시를 원래대로
    }

    // 로고 아이템은 종류별로 하나만 허용(uk_item_logo_type)하므로, 이미 있으면 그걸 쓰고 이 테스트가 넣은 것만 기록해 두었다가 지운다
    private Long logoItem(String type, long price) {
        insertedItemIds.addAll(jdbcTemplate.queryForList("""
                INSERT INTO item (name, content, type, pay_type, price, duration, multiplier)
                VALUES (?, '테스트 로고 변경권', ?, 'CREDIT', ?, NULL, NULL)
                ON CONFLICT DO NOTHING
                RETURNING id
                """, Long.class, "크레딧구매테스트 " + type, type, price));
        return jdbcTemplate.queryForObject("SELECT id FROM item WHERE type = ?", Long.class, type);
    }

    private MockMultipartFile logoImage() {
        return new MockMultipartFile("logoImage", "logo.png", "image/png", PNG_BYTES);
    }

    // 이 회원 폴더(custom-logo/{userId}/)에 남아 있는 이미지 수. userId가 테스트마다 새로 생겨서 다른 테스트와 섞이지 않는다
    private int uploadedLogoCount() {
        return s3Template.listObjects(bucket, "custom-logo/" + userId + "/").size();
    }

    private long priceOf(Long itemId) {
        return jdbcTemplate.queryForObject("SELECT price FROM item WHERE id = ?", Long.class, itemId);
    }

    private void setCredit(long balance) {
        jdbcTemplate.update("UPDATE users SET credit_balance = ? WHERE id = ?", balance, userId);
    }

    private long credit() {
        return jdbcTemplate.queryForObject("SELECT credit_balance FROM users WHERE id = ?", Long.class, userId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    private String logoOf(String table, Long id) {
        return jdbcTemplate.queryForObject("SELECT logo_url FROM " + table + " WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("학교 로고 변경권 구매가 커밋되면 크레딧 차감·구매 이력·사용 내역·학교 로고가 한꺼번에 반영되고, 이미지가 S3에 남는다")
    void purchase_universityLogo_commitsAll() {
        // given: 가격보다 1100 많은 크레딧
        long price = priceOf(universityLogoItemId);
        setCredit(price + 1100);

        // when
        itemPurchaseService.purchase(userId, universityId, departmentId, universityLogoItemId, logoImage(), UUID.randomUUID());

        // then
        assertThat(credit()).isEqualTo(1100L);
        assertThat(count("user_item")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT type, amount, balance_after FROM credit_history WHERE user_id = ?", userId))
                .containsEntry("type", "USE")
                .containsEntry("amount", price)
                .containsEntry("balance_after", 1100L);
        // 로고 주소: {공개 주소}/custom-logo/{회원}/{UUID}.png
        assertThat(logoOf("university", universityId))
                .startsWith(publicBaseUrl + "/custom-logo/" + userId + "/")
                .endsWith(".png");
        assertThat(uploadedLogoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("개인 로고 변경권을 사면 올린 이미지 주소로 프로필 이미지가 바뀐다")
    void purchase_profileLogo_changesProfileImage() {
        long price = priceOf(profileLogoItemId);
        setCredit(price);

        itemPurchaseService.purchase(userId, universityId, departmentId, profileLogoItemId, logoImage(), UUID.randomUUID());

        assertThat(credit()).isZero();
        assertThat(count("user_item")).isEqualTo(1L);
        // 주소는 서버가 만든다 (클라이언트가 URL을 보낼 수 없음)
        assertThat(logoOf("users", userId))
                .startsWith(publicBaseUrl + "/custom-logo/" + userId + "/")
                .endsWith(".png");
        assertThat(uploadedLogoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("크레딧이 부족하면 ITEM_NOT_ENOUGH_CREDIT이고 아무것도 바뀌지 않으며, 올린 이미지도 지운다")
    void purchase_notEnoughCredit_changesNothing() {
        // given: 가격보다 1 적은 크레딧
        long price = priceOf(universityLogoItemId);
        setCredit(price - 1);

        // when & then
        assertThatThrownBy(() -> itemPurchaseService.purchase(userId, universityId, departmentId, universityLogoItemId, logoImage(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH_CREDIT);

        assertThat(credit()).isEqualTo(price - 1);
        assertThat(count("user_item")).isZero();
        assertThat(count("credit_history")).isZero();
        assertThat(logoOf("university", universityId)).isNull();
        assertThat(uploadedLogoCount()).isZero();
    }

    @Test
    @DisplayName("크레딧 차감 뒤 로고 적용에서 실패하면 DB는 전부 롤백되고, 올린 이미지도 지운다")
    void purchase_failsAfterDeduct_rollsBackAll() {
        // given: 크레딧은 충분하지만, 토큰의 학과가 DB에 없음 → 차감·이력 저장 후 로고 적용 단계에서 실패
        long price = priceOf(departmentLogoItemId);
        setCredit(price + 1000);

        // when & then
        assertThatThrownBy(() -> itemPurchaseService.purchase(userId, universityId, 999_999L, departmentLogoItemId, logoImage(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNIV_DEPARTMENT_NOT_FOUND);

        // then: 앞에서 실행된 크레딧 차감(JDBC)과 이력·사용 내역 저장(JPA)이 모두 취소됨
        assertThat(credit()).isEqualTo(price + 1000);
        assertThat(count("user_item")).isZero();
        assertThat(count("credit_history")).isZero();
        // 트랜잭션 밖에서 먼저 올린 이미지는 롤백되지 않으므로 직접 지웠는지 확인
        assertThat(uploadedLogoCount()).isZero();
    }

    @Test
    @DisplayName("같은 회원이 동시에 10번 사도 잔액만큼만 성공하고, 크레딧은 음수가 되지 않으며, S3에는 성공한 만큼만 남는다")
    void concurrentPurchase_noOverspend() throws InterruptedException {
        // given: 정확히 2번 살 수 있는 크레딧 + 100
        long price = priceOf(universityLogoItemId);
        setCredit(price * 2 + 100);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notEnoughCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        // when
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        itemPurchaseService.purchase(userId, universityId, departmentId, universityLogoItemId, logoImage(), UUID.randomUUID());
                        successCount.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.ITEM_NOT_ENOUGH_CREDIT) {
                            notEnoughCount.incrementAndGet();   // 정상적인 실패
                        } else {
                            unexpectedErrors.add(e);
                        }
                    } catch (Throwable e) {
                        unexpectedErrors.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            // 이제 요청마다 S3 업로드(실패 시 삭제)가 붙어서 기존 10초보다 넉넉히
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // then
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(notEnoughCount.get()).isEqualTo(8);
        assertThat(credit()).isEqualTo(100L);
        assertThat(count("user_item")).isEqualTo(2L);
        // 사용 내역의 차감 후 잔액이 겹치지 않음 (두 구매가 차례로 반영됨)
        assertThat(jdbcTemplate.queryForList(
                "SELECT balance_after FROM credit_history WHERE user_id = ? ORDER BY balance_after DESC", Long.class, userId))
                .containsExactly(price + 100, 100L);
        // 실패한 8건이 올린 이미지는 지워지고 성공한 2건만 남음
        assertThat(uploadedLogoCount()).isEqualTo(2);
    }
}
