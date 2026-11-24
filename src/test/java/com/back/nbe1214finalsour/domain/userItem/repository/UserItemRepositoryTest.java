package com.back.nbe1214finalsour.domain.userItem.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
public class UserItemRepositoryTest {

    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 11, 17, 3, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private UserItemRepository userItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long me;
    private long other;
    private long potionId;

    @BeforeEach
    void setUp() {
        long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('구매내역테스트대') RETURNING id", Long.class);

        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class, universityId);

        me = insertUser(universityId, departmentId, "구매내역나", "a");

        other = insertUser(universityId, departmentId, "구매내역남", "b");

        potionId = jdbcTemplate.queryForObject("""
                INSERT INTO item (name, content, type, pay_type, price, duration, multiplier)
                VALUES ('구매내역테스트 물약', '1시간 동안 클릭 점수 2배', 'MULTIPLIER', 'CONTRIBUTION', 1000, 3600, 2.0)
                RETURNING id
                """, Long.class);
    }

    private long insertUser(long universityId, long departmentId, String nickname, String hashChar) {
        return this.jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, ?, 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, nickname, hashChar.repeat(64));
    }

    private long insertUserItem(long userId, OffsetDateTime createdAt) {
        return this.jdbcTemplate.queryForObject(
                "INSERT INTO user_item (user_id, item_id, price, expired_at, created_at) "
                        + "VALUES (?, ?, 1000, ?, ?) RETURNING id",
                Long.class, userId, potionId, createdAt.plusHours(1), createdAt);
    }

    @Test
    @DisplayName("구매 내역은 내 구매만 구매 시각 최신 순으로 돌려준다.")
    void findsOnlyMineLastestFirst() {
        // given: 구매 내역 3건, 다른 회원 구매 내역 1건(가장 최근)
        long first = insertUserItem(me, BASE);
        long third = insertUserItem(me, BASE.plusHours(2));
        long second = insertUserItem(me, BASE.plusHours(1));
        insertUserItem(other, BASE.plusHours(3));

        // when
        Page<UserItem> page = this.userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(me, PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).extracting(UserItem::getId).containsExactly(third, second, first);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
    @Test
    @DisplayName("구매 시각이 같으면 나중에 저장된(id가 큰) 구매가 먼저 나온다")
    void sameCreatedAt_laterIdFirst() {
        // given: 같은 시각에 2건
        long earlier = insertUserItem(me, BASE);
        long later = insertUserItem(me, BASE);

        // when
        Page<UserItem> page = userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(me, PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).extracting(UserItem::getId).containsExactly(later, earlier);
    }

    @Test
    @DisplayName("구매 내역을 요청한 크기로 나눠 돌려주고, 전체 개수와 다음 페이지 여부를 알려준다")
    void paginates() {
        // given: 3건
        long first = insertUserItem(me, BASE);
        long second = insertUserItem(me, BASE.plusHours(1));
        long third = insertUserItem(me, BASE.plusHours(2));

        // when: 2개씩
        Page<UserItem> page0 = userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(me, PageRequest.of(0, 2));
        Page<UserItem> page1 = userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(me, PageRequest.of(1, 2));

        // then
        assertThat(page0.getContent()).extracting(UserItem::getId).containsExactly(third, second);
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.hasNext()).isTrue();

        assertThat(page1.getContent()).extracting(UserItem::getId).containsExactly(first);
        assertThat(page1.hasNext()).isFalse();
    }
}

