package com.strider.chat.config;

import com.strider.strider_common_lib.utils.TokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;


import java.security.Principal;

/**
 * STOMP 연결 시 JWT 인증 인터셉터
 *
 * ■ 동작:
 *   STOMP CONNECT 프레임에서 Authorization 헤더의 JWT를 검증하고
 *   userId를 Principal로 설정한다.
 *   이후 @MessageMapping 핸들러에서 Principal.getName()으로 userId 추출 가능.
 *
 * ■ 인증 실패 시:
 *   예외가 발생하면 STOMP 연결이 거부된다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private final TokenUtils tokenUtils;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("[STOMP] 인증 실패 — Authorization 헤더 누락");
                throw new IllegalArgumentException("STOMP 연결에 Authorization 헤더가 필요합니다");
            }

            // TokenUtils는 HttpHeaders를 받으므로 변환
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);

            String userId = tokenUtils.getUidFrom(headers);
            log.info("[STOMP] 인증 성공 | userId: {}", userId);

            // Principal 설정 → @MessageMapping에서 principal.getName()으로 접근
            accessor.setUser(new StompPrincipal(userId));
        }

        return message;
    }

    /**
     * STOMP 세션에 바인딩할 Principal 구현체
     */
    private record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
