package com.back.nbe1214finalsour.domain.chat.websocket;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 거절 ERROR 프레임을 보낸 뒤에 세션을 닫는다 (GitHub #148, SCRUM-244).
 *
 * <p>Spring은 프레임을 거절하면 ERROR를 보내고 곧바로 {@code close(PROTOCOL_ERROR)}를 부른다. 그 순간 다른 스레드가
 * 같은 세션에 쓰고 있으면(예: 막 보낸 CONNECTED) ERROR는 부모의 대기열에만 들어가고, {@code close()}가 켠 표시 때문에
 * 그 스레드가 ERROR를 버린다. 클라이언트는 오류 코드 없이 끊긴 연결만 받는다.
 *
 * <p>그래서 {@code PROTOCOL_ERROR}로 닫을 때만 다음을 한다.
 * <ol>
 *   <li>진행 중인 전송이 끝나기를 기다린다. 최대 대기 시간은 보내기 시간 한도({@code send-time-limit})의 남은 시간이다.</li>
 *   <li>그래도 대기열에 프레임이 남았으면 직접 마저 보낸다. 쓰던 스레드가 "더 보낼 것 없음"을 확인한 뒤 잠금을 풀기 전에
 *       ERROR가 들어오면, 부모는 다시 시도하지 않아 ERROR를 보낼 스레드가 없다.</li>
 * </ol>
 * 닫기 시작한 뒤 들어온 전송은 보내지 않는다. ERROR 다음 프레임은 의미가 없다.
 *
 * <p>Spring 이슈 <a href="https://github.com/spring-projects/spring-framework/issues/37328">#37328</a>이 고쳐지면 이 클래스를 지운다.
 */
@Slf4j
public class ErrorFrameFlushingSessionDecorator extends ConcurrentWebSocketSessionDecorator {

    private final ReentrantLock sendsLock = new ReentrantLock();
    private final Condition allSendsFinished = this.sendsLock.newCondition();
    private int sendsInProgress;
    private boolean closing;

    public ErrorFrameFlushingSessionDecorator(WebSocketSession delegate, int sendTimeLimit, int bufferSizeLimit) {
        super(delegate, sendTimeLimit, bufferSizeLimit);
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        this.sendsLock.lock();
        try {
            if (this.closing) {
                return;
            }
            this.sendsInProgress++;
        } finally {
            this.sendsLock.unlock();
        }
        try {
            super.sendMessage(message);
        } finally {
            this.sendsLock.lock();
            try {
                if (--this.sendsInProgress == 0) {
                    this.allSendsFinished.signalAll();
                }
            } finally {
                this.sendsLock.unlock();
            }
        }
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        boolean idle;
        this.sendsLock.lock();
        try {
            this.closing = true;
            idle = status.equalsCode(CloseStatus.PROTOCOL_ERROR) && awaitSendsFinished();
        } finally {
            this.sendsLock.unlock();
        }
        if (idle && getBufferSize() > 0) {
            flushRemaining();
        }
        super.close(status);
    }

    /** sendsLock을 쥔 채 부른다. 진행 중인 전송이 모두 끝났으면 true, 시간이 다 되거나 인터럽트되면 false */
    private boolean awaitSendsFinished() {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, getSendTimeLimit() - getTimeSinceSendStarted()));
        while (this.sendsInProgress > 0) {
            if (remainingNanos <= 0) {
                log.debug("진행 중인 전송이 끝나지 않아 기다리지 않고 닫습니다: session={}", getId());
                return false;
            }
            try {
                remainingNanos = this.allSendsFinished.awaitNanos(remainingNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * 빈 ping을 대기열 끝에 넣어 부모가 이 스레드에서 대기열을 앞에서부터 비우게 한다. 남은 ERROR가 먼저 나가고 ping이 뒤따른다.
     * ping은 WebSocket 제어 프레임이라 STOMP 클라이언트는 보지 않는다. 부모의 비공개 대기열을 직접 건드리지 않으려고 이렇게 한다.
     */
    private void flushRemaining() {
        try {
            super.sendMessage(new PingMessage());
        } catch (IOException | RuntimeException failed) {
            // 이미 끊긴 연결이면 보낼 수 없다. 닫기는 계속한다
            log.debug("닫기 전에 남은 프레임을 보내지 못했습니다: session={}", getId(), failed);
        }
    }
}
