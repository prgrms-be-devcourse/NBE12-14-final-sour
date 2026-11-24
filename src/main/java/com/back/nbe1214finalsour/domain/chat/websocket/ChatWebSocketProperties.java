package com.back.nbe1214finalsour.domain.chat.websocket;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 채팅 WebSocket 전송 한도 ({@code chat.websocket.*}, 계획 D23). 넘으면 Spring이 그 연결을 끊는다.
 *
 * @param messageSizeLimit   클라이언트가 보내는 STOMP 메시지 하나의 최대 크기. 본문 200자(UTF-8 약 600바이트)와 CONNECT의 JWT 헤더를 담고도 남는 8KB.
 *                           크게 키우면 Tomcat WebSocket 텍스트 버퍼(기본 8192)가 먼저 막을 수 있으니 함께 확인한다
 * @param timeToFirstMessage 연결 뒤 첫 메시지(CONNECT)를 기다리는 시간. 연결만 열어 두는 소켓을 빨리 정리한다
 * @param sendTimeLimit      서버가 한 클라이언트에 보내는 데 쓸 수 있는 시간 (Spring 기본값 10초)
 * @param sendBufferSizeLimit 느린 클라이언트에게 보내지 못하고 쌓아 둘 수 있는 크기 (Spring 기본값 512KB)
 */
@Validated
@ConfigurationProperties("chat.websocket")
public record ChatWebSocketProperties(
        @NotNull DataSize messageSizeLimit,
        @NotNull Duration timeToFirstMessage,
        @NotNull Duration sendTimeLimit,
        @NotNull DataSize sendBufferSizeLimit) {
}
