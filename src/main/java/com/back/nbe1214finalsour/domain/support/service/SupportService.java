package com.back.nbe1214finalsour.domain.support.service;

import com.back.nbe1214finalsour.domain.support.dto.SupportCreateRequest;
import com.back.nbe1214finalsour.domain.support.dto.SupportCreateResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportDetailResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportListResponse;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.domain.support.validation.SupportAttachmentUrlPolicy;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원·비회원 고객 문의 (추가 명세 §3-8) */
@Service
@RequiredArgsConstructor
public class SupportService {

    private final CustomerSupportRepository repository;
    private final SupportAttachmentUrlPolicy attachmentUrlPolicy;
    private final SupportRateLimiter rateLimiter;
    private final SupportBotCheck botCheck;

    /**
     * 문의를 접수한다. 첨부 검사 → (비회원) 봇 확인 → 횟수 한도 → 저장 순서다. 첨부가 틀리거나 봇 확인에 걸린 요청은
     * 한도를 쓰지 않는다 (계획 Q8, SCRUM-119).
     *
     * <p>정지·차단 회원도 등록할 수 있다. 이용 제한에 대한 문의가 이 경로로 들어오기 때문이다.
     *
     * @param userId   로그인한 회원, 비회원이면 {@code null}
     * @param clientIp 비회원 한도에 쓰는 요청 IP
     * @throws BusinessException 400 {@code SUPPORT_ATTACHMENT_NOT_ALLOWED} 비회원 첨부, 400 {@code FILE_INVALID_URL}
     *                           허용되지 않은 첨부 주소, 403 {@code ABUSE_VERIFICATION_REQUIRED}·{@code ABUSE_VERIFICATION_FAILED}
     *                           비회원 봇 확인, 429 {@code COMMON_TOO_MANY_REQUESTS} 한도 초과
     */
    @Transactional
    public SupportCreateResponse create(@Nullable Long userId, String clientIp, SupportCreateRequest request) {
        String attachFileUrl = request.attachFileUrl();
        if (userId == null) {
            if (attachFileUrl != null) {
                throw new BusinessException(ErrorCode.SUPPORT_ATTACHMENT_NOT_ALLOWED);
            }
            botCheck.checkGuest(clientIp, request.turnstileToken());
            rateLimiter.checkGuest(clientIp);
        } else {
            if (attachFileUrl != null && !attachmentUrlPolicy.isAllowed(attachFileUrl, userId)) {
                throw new BusinessException(ErrorCode.FILE_INVALID_URL, "문의 첨부 주소 규칙 위반 userId=" + userId);
            }
            rateLimiter.checkMember(userId);
        }

        CustomerSupport support = repository.save(CustomerSupport.receive(
                userId, request.recipientEmail(), request.title(), request.content(), attachFileUrl));
        return new SupportCreateResponse(support.getId(), support.getStatus());
    }

    /** 내 문의 목록. 최신순 (계획 D5) */
    @Transactional(readOnly = true)
    public SupportListResponse getMine(Long userId, int page, int size) {
        return SupportListResponse.from(repository.findListByUserId(userId, PageRequest.of(page, size)));
    }

    /**
     * 내 문의 상세. 비회원 문의는 주인이 없으므로 누구에게도 보이지 않는다 (답변은 메일로만 받는다).
     *
     * @throws BusinessException 404 {@code SUPPORT_NOT_FOUND}, 403 {@code SUPPORT_NOT_OWNER}
     */
    @Transactional(readOnly = true)
    public SupportDetailResponse getDetail(Long userId, Long supportId) {
        CustomerSupport support = repository.findById(supportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_NOT_FOUND, "supportId=" + supportId));
        if (!userId.equals(support.getUserId())) {
            throw new BusinessException(ErrorCode.SUPPORT_NOT_OWNER,
                    "supportId=" + supportId + " userId=" + userId);
        }
        return SupportDetailResponse.from(support);
    }
}
