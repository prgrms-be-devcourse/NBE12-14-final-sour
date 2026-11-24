package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClickerRepository extends JpaRepository<Clicker, Long> {
    List<Clicker> findAllByOrderByIdAsc();
}
