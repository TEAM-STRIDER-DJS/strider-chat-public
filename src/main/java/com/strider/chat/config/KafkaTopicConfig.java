package com.strider.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 토픽 설정
 *
 * ■ 토픽 목록:
 *   chat.message.event — 메시지 저장 완료 이벤트
 *     → OutboxScheduler가 발행, ChatMessageConsumer가 소비 후 STOMP broadcast
 *
 * ■ GROUP 채팅 확장 고려:
 *   roomId 기반이므로 DIRECT/GROUP 구분 없이 동일 토픽 사용 가능
 *   메시지 내 roomId를 보고 /sub/chat/room/{roomId}로 broadcast
 *
 * ■ 파티션 전략:
 *   현재: 파티션 1개 (단일 서버, 순서 보장)
 *   확장: 파티션 수 늘리면 여러 Consumer가 병렬 처리 가능
 *         단, 같은 방(roomId) 메시지는 같은 파티션에 들어가야 순서 보장
 *         → 이후 Kafka key를 roomId로 설정하면 파티션 수 무관하게 순서 보장
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${chat.kafka.topic.message-event}")
    private String messageEventTopic;

    @Value("${chat.kafka.topic.push-notification-event}")
    private String pushNotificationEventTopic;

    /**
     * chat.message.event 토픽 생성
     *
     * 애플리케이션 시작 시 토픽이 없으면 자동 생성됨.
     * 이미 존재하면 기존 토픽 유지 (설정 충돌 없음).
     *
     * partitions: 파티션 수 (병렬 처리 단위)
     * replicas:   복제본 수 (현재 개발 환경이므로 1, 프로덕션에서는 3 권장)
     */
    @Bean
    public NewTopic chatMessageEventTopic() {
        return TopicBuilder
                .name(messageEventTopic)
                .partitions(1)   // 추후 수평 확장 시 증가 (roomId를 파티션 키로 사용)
                .replicas(1)     // 프로덕션: 3으로 변경 (장애 대비 복제본)
                .build();
    }

    /**
     * chat.push-notification.event 토픽 생성
     *
     * ■ 소비자: strider-notification 서비스
     *   → 오프라인 수신자에게 FCM 푸시 알림 전송
     */
    @Bean
    public NewTopic chatPushNotificationEventTopic() {
        return TopicBuilder
                .name(pushNotificationEventTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
