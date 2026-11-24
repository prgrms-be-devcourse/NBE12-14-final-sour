package com.back.nbe1214finalsour.domain.abuse.controller;

import com.back.nbe1214finalsour.domain.abuse.controller.docs.AbuseVerificationApiDocs;
import com.back.nbe1214finalsour.domain.abuse.dto.AbuseVerificationRequest;
import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/abuse")
public class AbuseVerificationController implements AbuseVerificationApiDocs {

    private final AbuseVerificationService abuseVerificationService;

    /**
     * 클릭 봇 확인 (SCRUM-118). 인증 필요. 통과하면 의심 표시를 지우고 본문 없는 204다. 표시가 없어도 204 (멱등).
     * 거절은 403 {@code ABUSE_VERIFICATION_FAILED}, 회원당 10분 10번을 넘으면 429.
     * 클라이언트 IP는 {@code getRemoteAddr()}다 (프록시 뒤 실제 IP는 {@code server.forward-headers-strategy}, #66).
     */
    @PostMapping("/verification")
    public ResponseEntity<Void> verify(
            @AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody AbuseVerificationRequest request,
            HttpServletRequest servletRequest) {
        abuseVerificationService.verify(authUser.userId(), request.turnstileToken(), servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
