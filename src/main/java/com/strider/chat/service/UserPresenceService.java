package com.strider.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 사용자 온라인 상태 및 활성 채팅방 추적 서비스 (Redis 기반)
 *
 * ■ 저장 키:
 *   online:user:{userId}    — WebSocket 연결 여부 (연결 시 set, 해제 시 del)
 *   active:room:{userId}    — 현재 보고 있는 채팅방 ID (입장 시 set, 퇴장/해제 시 del)
 *
 * ■ 알림 판단 흐름 (ChatMessageConsumer에서 사용):
 *   오프라인      → Kafka chat.push-notification.event 발행 → strider-notification이 FCM 처리
 *   온라인 + 다른 방 → STOMP /sub/user/{userId}/notification 직접 push
 *   온라인 + 같은 방 → 알림 없음 (메시지를 이미 보고 있음)
 *
 * ■ TTL (chat.presence.ttl-seconds):
 *   두 키 모두 만료 시간을 갖는다. 서버가 비정상 종료되거나 SessionDisconnectEvent를
 *   놓치면 삭제 코드가 실행되지 않는데, TTL이 없으면 그 사용자는 영원히 "온라인"으로
 *   남아 FCM 푸시를 한 번도 받지 못한다. TTL은 그런 유령 상태의 수명을 제한한다.
 *   연결이 살아 있는 동안에는 PresenceRefreshScheduler가 heartbeat()로 만료를 미룬다.
 */
@Service
@RequiredArgsConstructor
public class UserPresenceService {

    private final StringRedisTemplate redisTemplate;

    private static final String ONLINE_KEY_PREFIX = "online:user:";
    private static final String ACTIVE_ROOM_KEY_PREFIX = "active:room:";

    /** 상태 키 만료 시간. 갱신 주기보다 넉넉히 길어야 정상 연결이 중간에 끊기지 않는다. */
    @Value("${chat.presence.ttl-seconds}")
    private long ttlSeconds;

    /** WebSocket 연결 시 온라인 표시 */
    public void setOnline(String userId) {
        redisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, "1", ttl());
    }

    /** WebSocket 해제 시 온라인 표시 + 활성 방 모두 삭제 */
    public void setOffline(String userId) {
        redisTemplate.delete(ONLINE_KEY_PREFIX + userId);
        redisTemplate.delete(ACTIVE_ROOM_KEY_PREFIX + userId);
    }

    /** 채팅방 입장 시 현재 보고 있는 방 저장 */
    public void enterRoom(String userId, String roomId) {
        redisTemplate.opsForValue().set(ACTIVE_ROOM_KEY_PREFIX + userId, roomId, ttl());
    }

    /** 채팅방 퇴장 시 활성 방 삭제 */
    public void leaveRoom(String userId) {
        redisTemplate.delete(ACTIVE_ROOM_KEY_PREFIX + userId);
    }

    /**
     * 연결이 살아 있는 사용자의 만료 시각을 뒤로 미룬다
     *
     * ■ online:user는 expire가 아니라 다시 set 한다:
     *   Redis 재시작이나 긴 STW로 키가 이미 사라졌을 수 있는데, expire는 없는 키에
     *   아무 일도 하지 않으므로 접속 중인 사용자가 영구 오프라인으로 굳어버린다.
     *
     * ■ active:room은 expire만 한다:
     *   스케줄러는 사용자가 어느 방을 보고 있는지 모른다. 키가 이미 없다면 방에
     *   들어가 있지 않다는 뜻이므로 되살리면 안 된다.
     */
    public void heartbeat(String userId) {
        setOnline(userId);
        redisTemplate.expire(ACTIVE_ROOM_KEY_PREFIX + userId, ttl());
    }

    /** 온라인 여부 확인 */
    public boolean isOnline(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ONLINE_KEY_PREFIX + userId));
    }

    /** 현재 보고 있는 roomId 반환 (없으면 null) */
    public String getActiveRoom(String userId) {
        return redisTemplate.opsForValue().get(ACTIVE_ROOM_KEY_PREFIX + userId);
    }

    private Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }
}
