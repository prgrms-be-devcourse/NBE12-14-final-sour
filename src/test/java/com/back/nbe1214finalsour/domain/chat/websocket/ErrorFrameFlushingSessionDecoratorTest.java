package com.back.nbe1214finalsour.domain.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 거절 ERROR 프레임 유실(GitHub #148, Spring #37328)을 결정적으로 재현한다. 가짜 세션이 CONNECTED 쓰기를 래치에서 멈춰,
 * "다른 스레드가 쓰는 중에 ERROR를 보내고 닫는" 순서를 매번 같게 만든다.
 */
class ErrorFrameFlushingSessionDecoratorTest {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final String CONNECTED = "CONNECTED";
    private static final String ERROR = "ERROR";

    /** 가짜 세션이 실제로 보낸 프레임과 close를 순서대로 남긴다 */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch writingConnected = new CountDownLatch(1);
    private final CountDownLatch releaseConnected = new CountDownLatch(1);
    private final AtomicReference<Throwable> threadFailure = new AtomicReference<>();
    private volatile boolean failConnectedWrite;

    @AfterEach
    void releaseBlockedWriter() {
        this.releaseConnected.countDown();
    }

    private WebSocketSession delegate() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        doAnswer(call -> {
            String label = label(call.getArgument(0));
            if (label.equals(CONNECTED)) {
                this.writingConnected.countDown();
                this.releaseConnected.await();
                if (this.failConnectedWrite) {
                    throw new IOException("끊긴 연결");
                }
            }
            this.events.add("send:" + label);
            return null;
        }).when(session).sendMessage(any());
        doAnswer(call -> {
            this.events.add("close:" + call.<CloseStatus>getArgument(0).getCode());
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    private static String label(WebSocketMessage<?> message) {
        if (message instanceof PingMessage) {
            return "PING";
        }
        return ((TextMessage) message).getPayload();
    }

    /** 다른 스레드에서 CONNECTED를 보내기 시작하고, 가짜 세션에서 멈출 때까지 기다린다 */
    private Thread startWritingConnected(WebSocketSession session) throws InterruptedException {
        Thread writer = start(() -> session.sendMessage(new TextMessage(CONNECTED)));
        assertThat(this.writingConnected.await(5, TimeUnit.SECONDS)).isTrue();
        return writer;
    }

    /** 거절 경로와 같은 순서: ERROR를 보내고 곧바로 PROTOCOL_ERROR로 닫는다 */
    private Thread startRejecting(WebSocketSession session) {
        return start(() -> {
            session.sendMessage(new TextMessage(ERROR));
            session.close(CloseStatus.PROTOCOL_ERROR);
        });
    }

    private Thread start(ThrowingRunnable action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                this.threadFailure.compareAndSet(null, failure);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime()).as("스레드가 대기 상태가 되어야 한다").isLessThan(deadline);
            assertThat(thread.isAlive()).as("스레드가 기다리지 않고 끝났다").isTrue();
            Thread.sleep(5);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(thread.isAlive()).as("스레드가 5초 안에 끝나야 한다").isFalse();
    }

    @Test
    @DisplayName("Spring 기본 데코레이터는 다른 스레드가 쓰는 중에 거절하면 ERROR를 버린다 (Spring #37328. 이 테스트가 실패하면 Spring이 고친 것이니 우회 코드를 지운다)")
    void springDecoratorDropsErrorFrame() throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(delegate(), SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        Thread writer = startWritingConnected(session);

        Thread rejecting = startRejecting(session);
        join(rejecting);
        this.releaseConnected.countDown();
        join(writer);

        assertThat(this.threadFailure.get()).isNull();
        assertThat(this.events).containsExactly("close:1002", "send:" + CONNECTED);
    }

    @Test
    @DisplayName("다른 스레드가 쓰는 중에 거절하면, 그 전송이 끝나기를 기다렸다가 ERROR를 보낸 뒤 닫는다")
    void waitsForWriterThenSendsErrorBeforeClosing() throws Exception {
        WebSocketSession session = decorator(SEND_TIME_LIMIT_MS);
        Thread writer = startWritingConnected(session);

        Thread rejecting = startRejecting(session);
        awaitWaiting(rejecting);
        assertThat(this.events).as("쓰던 전송이 끝나기 전에는 닫지 않는다").isEmpty();
        this.releaseConnected.countDown();
        join(writer);
        join(rejecting);

        assertThat(this.threadFailure.get()).isNull();
        assertThat(this.events).containsExactly("send:" + CONNECTED, "send:" + ERROR, "close:1002");
    }

    @Test
    @DisplayName("진행 중인 전송이 없는데 대기열에 ERROR가 남아 있으면, 닫기 전에 마저 보낸다")
    void flushesFrameLeftInBufferBeforeClosing() throws Exception {
        WebSocketSession session = decorator(SEND_TIME_LIMIT_MS);
        // 쓰던 스레드가 대기열을 비우지 않고 나간 상태를 만든다. 실제로는 그 스레드가 "더 보낼 것 없음"을 확인한 뒤
        // 잠금을 풀기 전에 ERROR가 들어올 때 생긴다. 그 순간은 테스트에서 고정할 수 없어 쓰기 실패로 같은 상태를 만든다
        this.failConnectedWrite = true;
        Thread writer = startWritingConnected(session);
        session.sendMessage(new TextMessage(ERROR));
        this.releaseConnected.countDown();
        join(writer);
        assertThat(this.threadFailure.get()).isInstanceOf(IOException.class);
        assertThat(this.events).as("ERROR가 대기열에 남아 있다").isEmpty();

        session.close(CloseStatus.PROTOCOL_ERROR);

        assertThat(this.events).containsExactly("send:" + ERROR, "send:PING", "close:1002");
    }

    static Stream<CloseStatus> otherStatuses() {
        return Stream.of(CloseStatus.NORMAL, CloseStatus.SESSION_NOT_RELIABLE);
    }

    @ParameterizedTest(name = "닫기 코드 {0}")
    @MethodSource("otherStatuses")
    @DisplayName("PROTOCOL_ERROR가 아닌 닫기는 기다리지 않고 바로 닫는다")
    void otherStatusesCloseImmediately(CloseStatus status) throws Exception {
        WebSocketSession session = decorator(SEND_TIME_LIMIT_MS);
        startWritingConnected(session);

        Thread closing = start(() -> session.close(status));
        join(closing);

        assertThat(this.threadFailure.get()).isNull();
        assertThat(this.events).containsExactly("close:" + status.getCode());
    }

    @Test
    @DisplayName("닫기를 시작한 뒤 들어온 전송은 보내지 않는다 (ERROR 다음 프레임은 의미가 없다)")
    void dropsSendsAfterCloseStarted() throws Exception {
        WebSocketSession session = decorator(SEND_TIME_LIMIT_MS);
        Thread writer = startWritingConnected(session);
        Thread rejecting = startRejecting(session);
        awaitWaiting(rejecting);

        session.sendMessage(new TextMessage("MESSAGE"));
        this.releaseConnected.countDown();
        join(writer);
        join(rejecting);

        assertThat(this.threadFailure.get()).isNull();
        assertThat(this.events).containsExactly("send:" + CONNECTED, "send:" + ERROR, "close:1002");
    }

    @Test
    @DisplayName("진행 중인 전송이 끝나지 않아도 보내기 시간 한도 안에 닫는다")
    void closesWithinSendTimeLimitEvenIfWriterNeverFinishes() throws Exception {
        WebSocketSession session = decorator(300);
        startWritingConnected(session);

        long started = System.nanoTime();
        Thread rejecting = startRejecting(session);
        join(rejecting);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
        assertThat(this.threadFailure.get()).isNull();
        assertThat(this.events).hasSize(1).first().asString().startsWith("close:");
    }

    private WebSocketSession decorator(int sendTimeLimit) throws IOException {
        return new ErrorFrameFlushingSessionDecorator(delegate(), sendTimeLimit, BUFFER_SIZE_LIMIT);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
