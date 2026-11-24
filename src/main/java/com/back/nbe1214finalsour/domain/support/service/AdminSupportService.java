package com.back.nbe1214finalsour.domain.support.service;

import com.back.nbe1214finalsour.domain.support.dto.AdminSupportDetailResponse;
import com.back.nbe1214finalsour.domain.support.dto.AdminSupportListResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerRequest;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerResponse;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import com.back.nbe1214finalsour.domain.support.event.SupportAnsweredEvent;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 문의 목록·답변 (추가 명세 §3-9, SCRUM-112) */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSupportService {

    private final CustomerSupportRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 문의 목록. 최신순 (계획 D5). {@code status}를 생략하면 전체다 (계획 D6). 수신 이메일을 보여 주므로 엔티티로 읽어
     * 복호화한다.
     */
    @Transactional(readOnly = true)
    public AdminSupportListResponse getList(@Nullable SupportStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<CustomerSupport> result = status == null
                ? repository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : repository.findByStatusOrderByCreatedAtDescIdDesc(status, pageable);
        return AdminSupportListResponse.from(result);
    }

    /**
     * 문의 상세. 관리자는 작성자와 관계없이 모든 문의를 본다 (SCRUM-173).
     *
     * @throws BusinessException 404 {@code SUPPORT_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public AdminSupportDetailResponse getDetail(Long supportId) {
        return repository.findById(supportId)
                .map(AdminSupportDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_NOT_FOUND, "supportId=" + supportId));
    }

    /**
     * 접수 상태인 문의에 답변한다. 조건부 UPDATE라 두 관리자가 동시에 답변해도 한 건만 성공한다 (계획 Q5). 성공하면
     * {@link SupportAnsweredEvent}를 발행하고, 답변 메일은 커밋 뒤 따로 보낸다 (계획 Q6).
     *
     * @throws BusinessException 404 {@code SUPPORT_NOT_FOUND}, 409 {@code SUPPORT_ALREADY_ANSWERED}
     */
    @Transactional
    public SupportAnswerResponse answer(Long adminId, Long supportId, SupportAnswerRequest request) {
        if (repository.answer(supportId, request.answerTitle(), request.answerContent()) == 0) {
            if (repository.existsById(supportId)) {
                throw new BusinessException(ErrorCode.SUPPORT_ALREADY_ANSWERED, "supportId=" + supportId);
            }
            throw new BusinessException(ErrorCode.SUPPORT_NOT_FOUND, "supportId=" + supportId);
        }
        eventPublisher.publishEvent(new SupportAnsweredEvent(supportId));
        log.info("문의 답변 adminId={}, supportId={}", adminId, supportId);
        return new SupportAnswerResponse(supportId, SupportStatus.ANSWERED);
    }
}
