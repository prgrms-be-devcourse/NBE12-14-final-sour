package com.back.nbe1214finalsour.domain.item.dto;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.service.PurchaseHistory;
import org.springframework.data.domain.Page;

import java.time.OffsetDateTime;
import java.util.List;

// 아이템 구매 내역 (추가 명세 3-11). 정렬은 구매 시각 최신순
public record PurchaseHistoryResponse(
        List<HistoryItem> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {
    public static PurchaseHistoryResponse from(Page<PurchaseHistory> result) {
        return new PurchaseHistoryResponse(
                result.getContent().stream().map(HistoryItem::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.hasNext()
        );
    }

    // expiredAt은 로고(영구)면 null
    public record HistoryItem(
            Long userItemId,
            PurchasedItem item,
            long price,
            OffsetDateTime createdAt,
            OffsetDateTime expiredAt
    ) {
        static HistoryItem from(PurchaseHistory history) {
            return new HistoryItem(
                    history.userItemId(),
                    PurchasedItem.from(history.item()),
                    history.price(),
                    history.createdAt(),
                    history.expiredAt()
            );
        }
    }

    // 아이템 캐시에 없는 구매면 null
    public record PurchasedItem(Long id, String name, PayType payType) {
        static PurchasedItem from(Item item) {
            if (item == null) {
                return null;
            }
            return new PurchasedItem(item.getId(), item.getName(), item.getPayType());
        }
    }
}