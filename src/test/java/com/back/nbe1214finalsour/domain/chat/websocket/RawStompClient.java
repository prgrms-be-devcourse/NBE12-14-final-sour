package com.back.nbe1214finalsour.domain.chat.websocket;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 테스트용 STOMP 클라이언트. 프레임을 문자열 그대로 주고받아 ERROR 프레임의 헤더·본문과 연결 종료를 직접 확인한다.
 * ({@code WebSocketStompClient}는 ERROR 프레임과 종료를 콜백 안으로 감춘다)
 */
public final class RawStompClient implements AutoCloseable {

    private final WebSocketSession session;
    private final BlockingQueue<String> frames;
    private final CompletableFuture<CloseStatus> closed;

    private RawStompClient(WebSocketSession session, BlockingQueue<String> frames, CompletableFuture<CloseStatus> closed) {
        this.session = session;
        this.frames = frames;
        this.closed = closed;
    }

    public static RawStompClient open(int port, String origin) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                // heartbeat(줄바꿈만 있는 프레임)는 건너뛴다
                if (!payload.isBlank()) {
                    frames.add(payload.endsWith("\0") ? payload.substring(0, payload.length() - 1) : payload);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.complete(status);
            }
        };
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, headers, URI.create("ws://localhost:" + port + ChatWebSocketConfig.ENDPOINT))
                .get(5, TimeUnit.SECONDS);
        return new RawStompClient(session, frames, closed);
    }

    public void send(String command, String... headersThenBody) throws Exception {
        StringBuilder frame = new StringBuilder(command).append('\n');
        String body = "";
        for (String part : headersThenBody) {
            if (part.contains(":")) {
                frame.append(part).append('\n');
            } else {
                body = part;
            }
        }
        frame.append('\n').append(body).append('\0');
        this.session.sendMessage(new TextMessage(frame.toString()));
    }

    public void connect(@Nullable String accessToken) throws Exception {
        if (accessToken == null) {
            send("CONNECT", "accept-version:1.2", "host:localhost", "heart-beat:10000,10000");
        } else {
            send("CONNECT", "accept-version:1.2", "host:localhost", "heart-beat:10000,10000",
                    "Authorization:Bearer " + accessToken);
        }
    }

    /** 본문에 ':'가 들어가도 되도록 헤더와 본문을 따로 받는다 */
    public void sendJson(String destination, String body) throws Exception {
        this.session.sendMessage(new TextMessage(
                "SEND\ndestination:" + destination + "\ncontent-type:application/json\n\n" + body + "\0"));
    }

    public void subscribe(String id, String destination) throws Exception {
        send("SUBSCRIBE", "id:" + id, "destination:" + destination);
    }

    /** 다음 프레임. 시간 안에 없으면 null */
    public @Nullable String nextFrame(Duration timeout) throws InterruptedException {
        return this.frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public String requireFrame() throws InterruptedException {
        String frame = nextFrame(Duration.ofSeconds(5));
        if (frame == null) {
            throw new AssertionError("5초 안에 받은 STOMP 프레임 없음");
        }
        return frame;
    }

    /** 서버가 연결을 끊을 때까지 기다린다. 끊지 않으면 null */
    public @Nullable CloseStatus awaitClose(Duration timeout) {
        try {
            return this.closed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        if (this.session.isOpen()) {
            this.session.close();
        }
    }
}
