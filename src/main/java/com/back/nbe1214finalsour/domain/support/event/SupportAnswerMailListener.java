package com.back.nbe1214finalsour.domain.support.event;

import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import com.back.nbe1214finalsour.domain.support.mail.SupportAnswerMailSender;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.global.async.AsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 답변이 커밋된 뒤 문의 메일 전용 스레드에서 답변 메일을 보낸다 (계획 Q6, SCRUM-113). 롤백되면 보내지 않는다.
 *
 * <p>답변은 이미 커밋됐으므로 발송에 실패해도 되돌리지 않고 로그만 남긴다. 재시도는 없다 (MVP). 로그에는
 * {@code supportId}와 예외 종류만 남긴다. 메일 예외 메시지에는 수신 주소가 들어갈 수 있기 때문이다 (계획 D9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAnswerMailListener {

    private final CustomerSupportRepository repository;
    private final SupportAnswerMailSender mailSender;

    @Async(AsyncConfig.SUPPORT_MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnswered(SupportAnsweredEvent event) {
        Long supportId = event.supportId();
        try {
            CustomerSupport support = repository.findById(supportId).orElse(null);
            if (support == null || support.getStatus() != SupportStatus.ANSWERED) {
                log.warn("문의 답변 메일을 보내지 않음: 답변된 문의가 없음 supportId={}", supportId);
                return;
            }
            mailSender.sendAnswer(support.getRecipientEmail(), support.getTitle(),
                    support.getAnswerTitle(), support.getAnswerContent());
            log.info("문의 답변 메일 발송 supportId={}", supportId);
        } catch (RuntimeException e) {
            log.error("문의 답변 메일 발송 실패 supportId={}, error={}", supportId, e.getClass().getName());
        }
    }
}
