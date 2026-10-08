package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Outbox 패턴 엔티티
 *
 * ■ Outbox 패턴이란?
 *   메시지 저장(DB)과 이벤트 발행(Kafka)의 원자성(atomicity)을 보장하기 위한 패턴.
 *
 *   문제 상황:
 *   - Message DB 저장 성공 → Kafka 발행 실패 → 수신자가 메시지를 못 받음
 *   - Message DB 저장 실패 → Kafka 발행 성공 → 수신자는 받았는데 DB엔 없음
 *
 *   해결 방법:
 *   - Message 저장과 Outbox 저장을 같은 @Transactional 안에서 처리
 *   - Kafka 발행은 별도 스케줄러(OutboxScheduler)가 Outbox를 읽어서 처리
 *   - Kafka 발행 성공 시 Outbox 상태를 PUBLISHED로 변경
 *   - 실패해도 Outbox가 남아있으니 스케줄러가 다음 주기에 재시도
 *
 * ■ 동작 흐름:
 *   [ChatService.sendMessage()]
 *     1. Message 저장  ─┐
 *     2. Outbox 저장   ─┘ 같은 @Transactional → 둘 다 성공 or 둘 다 롤백
 *
 *   [OutboxScheduler — 5초마다]
 *     3. PENDING 상태 Outbox 조회
 *     4. Kafka chat.message.event 토픽에 발행 시도
 *        - 성공 → status: PUBLISHED
 *        - 실패 → retryCount +1, max 초과 시 FAILED
 *
 *   [ChatMessageConsumer]
 *     5. Kafka 메시지 소비 → STOMP broadcast
 *
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Outbox {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id", nullable = false, unique = true)
    private String outboxId;

    /**
     * 이벤트의 대상이 되는 집계(Aggregate) ID
     * 현재는 messageId — 어떤 메시지에 대한 이벤트인지 추적
     * 이후 GROUP 채팅 등 다른 도메인 이벤트도 이 컬럼으로 관리 가능
     */
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    /**
     * 이벤트 타입
     * Kafka Consumer에서 이벤트 종류를 구분하는 데 사용
     * 예: "CHAT_MESSAGE_CREATED"
     * 이후 확장: "CHAT_MESSAGE_DELETED", "CHAT_ROOM_CREATED" 등
     */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /**
     * Kafka에 실제로 발행할 페이로드 (JSON 문자열)
     * ChatMessageEvent 객체를 JSON 직렬화한 값이 저장됨
     * TEXT 타입으로 길이 제한 없음
     */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * Outbox 상태
     * - PENDING:   아직 Kafka에 발행되지 않음 (초기값)
     * - PUBLISHED: Kafka 발행 성공 → 처리 완료
     * - FAILED:    최대 재시도 횟수 초과 → 수동 확인 필요
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * Kafka 발행 재시도 횟수
     * application.yml의 max-retry-count 초과 시 FAILED 상태로 전환
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * Outbox 상태 열거형
     */
    public enum OutboxStatus {
        /** Kafka 발행 대기 중 */
        PENDING,
        /** Kafka 발행 완료 */
        PUBLISHED,
        /** 최대 재시도 초과로 실패 처리 (알림/모니터링 대상) */
        FAILED
    }
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막 상태 변경 시각 (PUBLISHED, FAILED 처리 시 갱신) */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
