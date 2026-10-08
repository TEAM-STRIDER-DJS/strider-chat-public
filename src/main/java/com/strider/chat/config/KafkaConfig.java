package com.strider.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strider.chat.kafka.event.ChatMessageEvent;
import com.strider.chat.kafka.event.PushNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer / Consumer 설정
 *
 * ■ 설계 구조:
 *   [Producer] ChatMessageProducer — Outbox 이벤트를 Kafka에 발행
 *   [Consumer] ChatMessageConsumer — Kafka 이벤트를 소비해 STOMP broadcast
 *
 * ■ 직렬화 전략:
 *   Producer: StringSerializer(key) + JsonSerializer(value)
 *   Consumer: StringDeserializer(key) + JsonDeserializer(value → ChatMessageEvent)
 *
 * ■ 그룹 ID (strider-chat-group):
 *   같은 그룹 내 여러 인스턴스가 파티션을 나눠 처리 (수평 확장 가능)
 *   현재는 단일 인스턴스이므로 하나가 모든 파티션 처리
 */
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    // Spring이 관리하는 ObjectMapper (JavaTimeModule 포함)
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    // ─────────────────────────────────────────────────
    // Producer 설정
    // ─────────────────────────────────────────────────

    /**
     * Kafka Producer 팩토리
     * OutboxScheduler → ChatMessageProducer → 이 팩토리로 Kafka에 발행
     */
    @Bean
    public ProducerFactory<String, ChatMessageEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        // Kafka 브로커 주소
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // 전송 신뢰성: 리더 복제본이 확인하면 성공 처리 (성능과 신뢰성의 균형)
        config.put(ProducerConfig.ACKS_CONFIG, "1");

        // Serializer를 직접 주입하므로 config에 KEY/VALUE_SERIALIZER_CLASS_CONFIG 설정 불필요
        // Spring ObjectMapper를 Kafka JsonSerializer에 주입 (LocalDateTime 직렬화 일관성 보장)
        JsonSerializer<ChatMessageEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(config, new StringSerializer(), valueSerializer);
    }

    /**
     * KafkaTemplate — ChatMessageProducer에서 실제 발행에 사용하는 템플릿
     */
    @Bean
    public KafkaTemplate<String, ChatMessageEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * PushNotificationEvent 전용 KafkaTemplate
     * ChatMessageConsumer에서 오프라인 수신자에게 알림 이벤트 발행 시 사용
     */
    @Bean
    public KafkaTemplate<String, PushNotificationEvent> pushNotificationKafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.ACKS_CONFIG, "1");

        JsonSerializer<PushNotificationEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), valueSerializer));
    }

    // ─────────────────────────────────────────────────
    // Consumer 설정
    // ─────────────────────────────────────────────────

    /**
     * Kafka Consumer 팩토리
     * @KafkaListener가 붙은 ChatMessageConsumer에서 사용
     */
    @Bean
    public ConsumerFactory<String, ChatMessageEvent> consumerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // 오프셋 초기화: 처음 접속 시 가장 오래된 메시지부터 처리
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Spring ObjectMapper를 Kafka JsonDeserializer에 주입 (LocalDateTime 역직렬화 일관성 보장)
        JsonDeserializer<ChatMessageEvent> valueDeserializer = new JsonDeserializer<>(ChatMessageEvent.class, objectMapper);
        valueDeserializer.setRemoveTypeHeaders(false);
        valueDeserializer.addTrustedPackages("com.strider.chat.kafka.event");
        valueDeserializer.setUseTypeMapperForKey(false);

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    /**
     * Kafka 리스너 컨테이너 팩토리
     * @KafkaListener 어노테이션과 연동되어 Consumer 스레드 관리
     * concurrency: 동시 처리 스레드 수 (파티션 수에 맞게 설정)
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // 동시 Consumer 스레드 수 (현재 1개 — 파티션 수에 맞게 확장 가능)
        factory.setConcurrency(1);
        return factory;
    }
}
