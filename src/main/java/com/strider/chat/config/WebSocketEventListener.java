package com.strider.chat.config;

import com.strider.chat.service.UserPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final UserPresenceService userPresenceService;

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;

        String userId = principal.getName();
        userPresenceService.setOnline(userId);
        log.info("[WebSocket] 연결 | userId: {}", userId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;

        String userId = principal.getName();
        userPresenceService.setOffline(userId);
        log.info("[WebSocket] 해제 | userId: {}", userId);
    }
}
