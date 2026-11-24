package com.back.nbe1214finalsour.domain.credit.repository;

import com.back.nbe1214finalsour.domain.credit.entity.CreditHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditHistoryRepository extends JpaRepository<CreditHistory, Long> {
}
