package com.strider.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket(STOMP) 설정 클래스
 *
 * ■ 인증:
 *   StompAuthInterceptor가 CONNECT 시 JWT를 검증하고 Principal을 설정한다.
 *   이후 @MessageMapping 핸들러에서 Principal.getName()으로 userId를 추출한다.
 *
 * ■ 주소 체계:
 *   - 클라이언트 → 서버: /pub/chat/send, /pub/chat/read
 *   - 서버 → 클라이언트: /sub/chat/room/{roomId}, /sub/chat/room/{roomId}/read
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor stompAuthInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/sub");
        registry.setApplicationDestinationPrefixes("/pub");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 네이티브 WebSocket 엔드포인트 (React Native 등)
        // withSockJS()를 붙이면 SockJS 핸들러만 등록되고 raw WebSocket은 등록되지 않으므로
        // 두 엔드포인트를 별도로 선언한다.
        registry
                .addEndpoint("/ws-chat")
                .setAllowedOriginPatterns("*");

        // // SockJS 엔드포인트 (브라우저 테스트 페이지)
        // registry
        //         .addEndpoint("/ws-chat-sockjs")
        //         .setAllowedOriginPatterns("*")
        //         .withSockJS();
    }

    /**
     * 인바운드 채널에 JWT 인증 인터셉터 등록
     * STOMP CONNECT 시 토큰 검증 → Principal 설정
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
