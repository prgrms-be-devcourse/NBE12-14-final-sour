package com.back.nbe1214finalsour.domain.userItem.repository;

import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    Page<UserItem> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
