package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.entity.UniDomain;
import com.back.nbe1214finalsour.domain.auth.repository.UniDomainRepository;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학교 이메일의 도메인으로 소속 학교를 정한다. 코드 요청·확인과 회원가입이 같은 규칙을 쓴다.
 *
 * <p>도메인은 정규화한 이메일의 마지막 {@code @} 뒤이고, {@code uni_domain}과 정확히 같아야 한다 (하위 도메인 불허).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailUniversityResolver {

    private final UniDomainRepository uniDomainRepository;
    private final UniversityRepository universityRepository;

    /**
     * @param email 정규화한 이메일 ({@code EmailHasher.normalize})
     * @throws BusinessException 등록되지 않은 도메인이면 {@code AUTH_UNSUPPORTED_DOMAIN}
     */
    public University resolve(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1);
        UniDomain uniDomain = uniDomainRepository.findByDomain(domain)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNSUPPORTED_DOMAIN, "domain=" + domain));
        return universityRepository.findById(uniDomain.getUniversityId())
                .orElseThrow(() -> new IllegalStateException("uni_domain의 학교가 없습니다: " + uniDomain.getUniversityId()));
    }
}
