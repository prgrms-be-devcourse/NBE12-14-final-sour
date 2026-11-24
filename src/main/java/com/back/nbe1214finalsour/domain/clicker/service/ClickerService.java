package com.back.nbe1214finalsour.domain.clicker.service;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.ClickerRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClickerService {

    private final ClickerRepository clickerRepository;

    private volatile List<Clicker> levelTable;

    public Clicker getClickerById(Long id) {
        return getLevelTable().stream().filter(c -> c.getId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR));
    }

    public List<Clicker> getLevelTable() {

        if (levelTable == null){
            loadLevelTable();
        }
        return this.levelTable;
    }

    // 서버가 시작될 때 미리 한 번 호출
    @EventListener(ApplicationReadyEvent.class)
    public void loadLevelTable() {
        // copyOf()로 불변 리스트 생성
        this.levelTable = List.copyOf(this.clickerRepository.findAllByOrderByIdAsc());
    }
}
