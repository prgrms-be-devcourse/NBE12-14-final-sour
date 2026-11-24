package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.userItem.repository.UserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseHistoryService {

    private final UserItemRepository userItemRepository;
    private final ItemService itemService;

    public Page<PurchaseHistory> findHistory(Long userId, int page, int size) {
        Map<Long, Item> items = this.itemService.getItems().stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        return this.userItemRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                .map(userItem -> new PurchaseHistory(
                        userItem.getId(),
                        items.get(userItem.getItemId()),
                        userItem.getPrice(),
                        userItem.getCreatedAt(),
                        userItem.getExpiredAt()
                ));
    }
}
