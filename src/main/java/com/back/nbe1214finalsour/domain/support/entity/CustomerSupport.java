package com.back.nbe1214finalsour.domain.support.entity;

import com.back.nbe1214finalsour.global.crypto.EncryptedEmailConverter;
import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 고객 문의 ({@code customer_support}). 비회원 문의는 {@code userId}가 {@code null}이다.
 *
 * <p>답변은 엔티티를 고치지 않고 {@code CustomerSupportRepository.answer}의 조건부 UPDATE로 넣는다. 두 관리자가 동시에
 * 답변해도 한 건만 성공하게 하기 위함이다 (계획 Q5).
 */
@Entity
@Table(name = "customer_support")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerSupport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 문의한 회원. 비회원이면 {@code null} */
    @Column(name = "user_id")
    private @Nullable Long userId;

    /** 답변 받을 이메일 원문. DB에는 암호문으로 저장된다 (V8) */
    @Convert(converter = EncryptedEmailConverter.class)
    @Column(name = "recipient_email", nullable = false, length = 512)
    private String recipientEmail;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 검사를 마친 우리 CDN 첨부 주소. 회원만 넣을 수 있다 */
    @Column(name = "attach_file_url", length = 500)
    private @Nullable String attachFileUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupportStatus status;

    @Column(name = "answer_title", length = 100)
    private @Nullable String answerTitle;

    @Column(name = "answer_content", columnDefinition = "TEXT")
    private @Nullable String answerContent;

    /**
     * 접수 상태의 문의를 만든다. 입력 검증(길이, 비회원 첨부 금지, 첨부 주소 규칙)은 호출한 쪽이 마친 값이어야 한다.
     *
     * @param userId         문의한 회원, 비회원이면 {@code null}
     * @param recipientEmail 앞뒤 공백을 지운 수신 이메일
     * @param attachFileUrl  첨부 주소, 없으면 {@code null}
     */
    public static CustomerSupport receive(@Nullable Long userId, String recipientEmail, String title, String content,
                                          @Nullable String attachFileUrl) {
        CustomerSupport support = new CustomerSupport();
        support.userId = userId;
        support.recipientEmail = recipientEmail;
        support.title = title;
        support.content = content;
        support.attachFileUrl = attachFileUrl;
        support.status = SupportStatus.RECEIVED;
        return support;
    }

    public boolean isMember() {
        return userId != null;
    }
}
