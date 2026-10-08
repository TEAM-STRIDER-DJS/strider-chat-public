package com.strider.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 접속 상태 키 TTL 갱신 스케줄러
 *
 * ■ 왜 필요한가:
 *   online:user / active:room 키에 TTL을 걸면 유령 상태는 사라지지만, 조용히 접속만
 *   유지하는 사용자의 키까지 만료된다. 그러면 실제로는 온라인인 사용자에게 FCM 푸시가
 *   나가고, 방을 보고 있는데도 인앱 알림이 뜬다.
 *
 * ■ 어떻게 판단하는가:
 *   SimpUserRegistry는 이 인스턴스에 STOMP로 연결된 사용자 목록을 들고 있다.
 *   클라이언트가 heartbeat 프레임을 보내주는지와 무관하게 서버가 직접 아는 값이므로,
 *   이 목록에 있는 사용자만 만료를 미룬다. 프로세스가 죽으면 갱신도 함께 멈추고
 *   남은 키는 TTL이 지나면서 자연히 정리된다.
 *
 * ■ 다중 인스턴스:
 *   레지스트리는 인스턴스별로 자기 세션만 담는다. 각 인스턴스가 자기 사용자만
 *   갱신하면 되므로 별도 조율이 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceRefreshScheduler {

    private final SimpUserRegistry simpUserRegistry;
    private final UserPresenceService userPresenceService;

    /**
     * 연결 중인 사용자의 상태 키 만료 시각을 미룬다
     *
     * 갱신 주기(chat.presence.refresh-interval-ms)는 TTL보다 충분히 짧아야 한다.
     * 한두 번 걸러도 키가 살아 있으려면 TTL의 1/3 이하를 권장한다.
     */
    @Scheduled(fixedDelayString = "${chat.presence.refresh-interval-ms}")
    public void refreshConnectedUsers() {
        Set<SimpUser> users = simpUserRegistry.getUsers();
        if (users.isEmpty()) return;

        for (SimpUser user : users) {
            try {
                userPresenceService.heartbeat(user.getName());
            } catch (Exception e) {
                // 한 명이 실패해도 나머지 갱신은 계속한다
                log.warn("[Presence] TTL 갱신 실패 | userId: {}, 원인: {}", user.getName(), e.getMessage());
            }
        }

        log.debug("[Presence] TTL 갱신 완료 | 대상: {}명", users.size());
    }
}
