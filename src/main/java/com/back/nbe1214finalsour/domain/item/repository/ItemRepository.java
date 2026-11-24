package com.back.nbe1214finalsour.domain.item.repository;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findAllByOrderByIdAsc();
}
