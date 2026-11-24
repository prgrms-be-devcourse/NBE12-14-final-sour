package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
public class ClickerRepositoryTest {

    @Autowired
    private ClickerRepository clickerRepository;

    @Test
    @DisplayName("클리커 레벨표 조회시 레벨 기준 오름차순으로 조회")
    void findAllByOrderByIdAsc(){
        // given: 시드 레벨과 겹치지 않는 레벨을 순서 없이 저장
        this.clickerRepository.saveAll(
                List.of(
                        new Clicker(102L, 30, 900L),
                        new Clicker(101L, 20, 300L)
                )
        );

        // when: 조회 시
        List<Clicker> result = this.clickerRepository.findAllByOrderByIdAsc();

        // then: 레벨 기준 오름차순 정렬 (시드가 있어도 이 테스트가 넣은 것만 확인)
        assertThat(result).extracting(Clicker::getId)
                .filteredOn(id -> id >= 101L)
                .containsExactly(101L, 102L);
    }
}
