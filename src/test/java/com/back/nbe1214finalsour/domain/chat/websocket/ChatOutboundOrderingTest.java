package com.back.nbe1214finalsour.domain.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.back.nbe1214finalsour.domain.chat.dto.ChatErrorResponse;
import com.back.nbe1214finalsour.domain.chat.service.AllianceChatMembership;
import com.back.nbe1214finalsour.domain.chat.service.ChatReceiveAuthorization;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.CorsConfig.CorsProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.DefaultSimpUserRegistry;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class ChatOutboundOrderingTest {

    @Test
    void queuedMessageIsRecheckedAndDroppingItReleasesTheNextMessage() throws InterruptedException {
        AuthUser receiver = new AuthUser(12L, 7L, null, null);
        AtomicBoolean member = new AtomicBoolean(true);
        UserRepository users = mock(UserRepository.class);
        when(users.existsByIdAndUniversityIdAndStatusAndDeletedAtIsNull(12L, 7L, UserStatus.ACTIVE))
                .thenReturn(true);
        AllianceChatMembership membership = mock(AllianceChatMembership.class);
        doAnswer(call -> {
            if (!member.get()) throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER);
            return null;
        }).when(membership).requireMember(receiver, 9L);

        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.setEnvironment(new MockEnvironment()
                    .withProperty("chat.websocket.message-size-limit", "8KB")
                    .withProperty("chat.websocket.time-to-first-message", "10s")
                    .withProperty("chat.websocket.send-time-limit", "10s")
                    .withProperty("chat.websocket.send-buffer-size-limit", "64KB"));
            context.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("chatStompInterceptor", mock(ChatStompInterceptor.class));
                factory.registerSingleton("stompErrorFrameHandler", mock(StompErrorFrameHandler.class));
                factory.registerSingleton("corsProperties", new CorsProperties(List.of("http://localhost")));
                factory.registerSingleton("chatReceiveAuthorization", new ChatReceiveAuthorization(users, membership));
            });
            context.register(ChatWebSocketConfig.class, ChatOutboundInterceptor.class);
            context.refresh();

            var channel = context.getBean("clientOutboundChannel", ExecutorSubscribableChannel.class);
            assertThat(channel.unsubscribe(context.getBean("subProtocolWebSocketHandler", MessageHandler.class))).isTrue();
            var sessionChannel = new OrderedMessageChannelDecorator(channel, LogFactory.getLog(getClass()));
            var received = new LinkedBlockingQueue<String>();
            var firstEntered = new CountDownLatch(1);
            var firstCanFinish = new CountDownLatch(1);
            channel.subscribe(frame -> {
                String body = new String((byte[]) frame.getPayload(), StandardCharsets.UTF_8);
                if (body.equals("first")) {
                    firstEntered.countDown();
                    try {
                        if (!firstCanFinish.await(5, TimeUnit.SECONDS)) throw new AssertionError("first frame timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                received.add(body);
            });
            context.publishEvent(new SessionConnectedEvent(this, message("/control", "connected"), new StompUser(receiver)));

            try {
                sessionChannel.send(message("/control", "first"));
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                sessionChannel.send(message(ChatDestinations.alliance(9), "no longer allowed"));
                sessionChannel.send(message(ChatDestinations.university(7), "still allowed"));
                member.set(false);
            } finally {
                firstCanFinish.countDown();
            }

            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("still allowed");
        }
    }

    @Test
    void unavailableStoreNotifiesOnlyTargetSessionAndDeliveryRecovers() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsByIdAndUniversityIdAndStatusAndDeletedAtIsNull(12L, 7L, UserStatus.ACTIVE))
                .thenThrow(new IllegalStateException("test store unavailable")).thenReturn(true);
        var channel = new ExecutorSubscribableChannel();
        var brokerChannel = new ExecutorSubscribableChannel();
        var broker = new SimpleBrokerMessageHandler(new ExecutorSubscribableChannel(), channel, brokerChannel, List.of("/topic", "/queue"));
        var registry = new DefaultSimpUserRegistry();
        var resolver = new DefaultUserDestinationResolver(registry);
        var destinations = new UserDestinationMessageHandler(new ExecutorSubscribableChannel(), brokerChannel, resolver);
        var auth = new ChatOutboundInterceptor(new ChatReceiveAuthorization(users, mock(AllianceChatMembership.class)),
                new SimpMessagingTemplate(brokerChannel));
        // Match the application configuration's executor ordering.
        broker.setPreservePublishOrder(true);
        channel.addInterceptor(auth);
        var messages = new ArrayList<Message<?>>();
        channel.subscribe(messages::add);
        var receiver = new StompUser(new AuthUser(12L, 7L, null, null));
        broker.start();
        destinations.start();
        try {
            for (String session : List.of("receiver-session", "other-tab")) {
                broker.handleMessage(event(SimpMessageType.CONNECT, session, null, null));
                var connected = new SessionConnectedEvent(this, event(SimpMessageType.CONNECT_ACK, session, null, null), receiver);
                auth.connected(connected);
                registry.onApplicationEvent(connected);
                destinations.handleMessage(event(SimpMessageType.SUBSCRIBE, session, ChatDestinations.ERROR_QUEUE, "err"));
            }
            broker.handleMessage(event(SimpMessageType.SUBSCRIBE, "receiver-session", ChatDestinations.university(7), "school"));
            messages.clear();
            var disconnect = new SessionDisconnectEvent(this, event(SimpMessageType.DISCONNECT, "receiver-session", null, null),
                    "receiver-session", CloseStatus.NORMAL, receiver);
            // Registry cleanup can precede the outbound interceptor receiving the same event.
            // Routing by member name at this point would deliver the session's error to the other tab.
            registry.onApplicationEvent(disconnect);

            brokerChannel.send(message(ChatDestinations.university(7), "not allowed when store unavailable"));
            brokerChannel.send(message(ChatDestinations.university(7), "allowed after recovery"));

            assertThat(messages).hasSize(2);
            assertThat(SimpMessageHeaderAccessor.getSessionId(messages.getFirst().getHeaders())).isEqualTo("receiver-session");
            assertThat(SimpMessageHeaderAccessor.getSubscriptionId(messages.getFirst().getHeaders())).isEqualTo("err");
            assertThat(messages.getFirst().getPayload()).isEqualTo(ChatErrorResponse.of(ErrorCode.COMMON_INTERNAL_ERROR));
            assertThat(messages.getLast().getPayload()).isEqualTo("allowed after recovery".getBytes(StandardCharsets.UTF_8));

            auth.disconnected(disconnect);
            brokerChannel.send(message(ChatDestinations.university(7), "disconnected recipient"));
            assertThat(messages).hasSize(2); // No body or notification without a known recipient.
        } finally {
            destinations.stop();
            broker.stop();
        }
    }

    private static Message<byte[]> event(SimpMessageType type, String session, String destination, String subscription) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(session);
        headers.setDestination(destination);
        headers.setSubscriptionId(subscription);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static Message<byte[]> message(String destination, String body) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        headers.setSessionId("receiver-session");
        headers.setUser(new StompUser(new AuthUser(99L, 99L, null, null))); // 발행자는 수신 권한의 근거가 아니다.
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(body.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders());
    }
}
