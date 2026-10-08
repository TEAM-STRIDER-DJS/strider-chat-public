package com.strider.chat.kafka.producer;

import com.strider.chat.kafka.event.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka 채팅 메시지 이벤트 Producer
 *
 * ■ 역할:
 *   OutboxScheduler가 PENDING 상태의 Outbox를 읽어와서
 *   이 Producer를 통해 Kafka chat.message.event 토픽에 발행
 *
 * ■ 파티션 키 전략:
 *   키를 roomId로 설정 → 같은 방의 메시지는 항상 같은 파티션에 저장
 *   → 파티션 내에서 순서 보장 (메시지 순서 유지)
 *   → 파티션 수를 늘려도 같은 방의 메시지 순서는 깨지지 않음
 *
 * ■ 비동기 전송:
 *   KafkaTemplate.send()는 CompletableFuture 반환
 *   OutboxScheduler에서 성공/실패 콜백을 받아 Outbox 상태 업데이트
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageProducer {

    private final KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    @Value("${chat.kafka.topic.message-event}")
    private String messageEventTopic;

    /**
     * 채팅 메시지 이벤트를 Kafka에 발행
     *
     * ■ 파티션 키 = roomId:
     *   같은 roomId의 메시지는 같은 파티션으로 라우팅
     *   → 파티션 내 FIFO 보장 → 채팅 메시지 순서 보장
     *
     * @param event Kafka에 발행할 ChatMessageEvent
     * @return CompletableFuture — 발행 성공/실패 결과 (OutboxScheduler에서 처리)
     */
    public CompletableFuture<SendResult<String, ChatMessageEvent>> send(ChatMessageEvent event) {
        log.debug("[Kafka Producer] 발행 시도 | topic: {}, roomId: {}, messageId: {}",
                messageEventTopic, event.getRoomId(), event.getMessageId());

        // roomId를 파티션 키로 사용 → 같은 방의 메시지 순서 보장
        CompletableFuture<SendResult<String, ChatMessageEvent>> future =
                kafkaTemplate.send(messageEventTopic, event.getRoomId(), event); // topic, key, event

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // 발행 실패 — OutboxScheduler가 retryCount를 올리고 다음 주기에 재시도
                log.error("[Kafka Producer] 발행 실패 | messageId: {}, 원인: {}",
                        event.getMessageId(), ex.getMessage());
            } else {
                // 발행 성공 — 파티션/오프셋 정보 로깅
                log.debug("[Kafka Producer] 발행 성공 | messageId: {}, partition: {}, offset: {}",
                        event.getMessageId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });

        return future;
    }
}
