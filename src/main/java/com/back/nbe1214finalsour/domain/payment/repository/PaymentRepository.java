package com.back.nbe1214finalsour.domain.payment.repository;

import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {}
