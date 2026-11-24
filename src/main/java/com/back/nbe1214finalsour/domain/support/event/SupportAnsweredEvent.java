package com.back.nbe1214finalsour.domain.support.event;

/**
 * 관리자가 문의에 답변했다 (SCRUM-112). 답변 트랜잭션이 커밋된 뒤 답변 메일을 보내는 데 쓴다 (계획 Q6).
 * 이메일 주소는 담지 않고, 받는 쪽이 {@code supportId}로 다시 읽는다.
 */
public record SupportAnsweredEvent(Long supportId) {
}
