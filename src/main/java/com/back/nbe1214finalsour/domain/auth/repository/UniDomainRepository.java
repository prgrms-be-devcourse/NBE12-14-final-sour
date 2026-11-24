package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.domain.auth.entity.UniDomain;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UniDomainRepository extends JpaRepository<UniDomain, Long> {

    /** @param domain 소문자로 정규화한 도메인 ({@code ck_uni_domain_lowercase}) */
    Optional<UniDomain> findByDomain(String domain);
}
