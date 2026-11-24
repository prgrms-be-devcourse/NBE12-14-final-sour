package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;

    // 아이템은 마이그레이션으로만 바뀌는 마스터 데이터라 서버 메모리에 캐시한다 (배포·재시작 때 다시 읽음)
    private volatile List<Item> items;

    public List<Item> getItems() {
        if (items == null) {
            loadItemTable();
        }
        return this.items;
    }

    // 서버가 시작될 때 미리 한번 호출
    @EventListener(ApplicationReadyEvent.class)
    public void loadItemTable(){
        // copyOf()로 불변 리스트 생성
        this.items = List.copyOf(this.itemRepository.findAllByOrderByIdAsc());
    }
}
