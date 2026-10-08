package com.strider.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strider.chat.kafka.event.ChatMessageEvent;
import com.strider.chat.kafka.producer.ChatMessageProducer;
import com.strider.chat.model.entity.Outbox;
import com.strider.chat.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 이벤트 발행 스케줄러
 *
 * ■ 역할:
 *   PENDING 상태의 Outbox 레코드를 주기적으로 읽어 Kafka에 발행.
 *   이를 통해 DB 저장과 Kafka 발행 간의 원자성을 보장하는
 *   "Outbox 패턴"의 핵심 컴포넌트.
 *
 * ■ 실행 주기:
 *   application.yml의 chat.outbox.scheduler-delay-ms 값으로 설정 (기본 5초)
 *
 * ■ 처리 흐름:
 *   1. PENDING 상태 Outbox 최대 50건 조회
 *   2. 각 Outbox의 payload(JSON)를 ChatMessageEvent로 역직렬화
 *   3. ChatMessageProducer로 Kafka에 발행 (비동기)
 *   4. 발행 성공 → Outbox 상태: PUBLISHED
 *      발행 실패 → retryCount +1, max 초과 시 FAILED
 *
 * ■ @EnableScheduling:
 *   StriderChatApplication에 @EnableScheduling이 있어야 활성화됨
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxRepository outboxRepository;
    private final ChatMessageProducer chatMessageProducer;

    /**
     * JSON 역직렬화용 ObjectMapper
     * Outbox.payload (JSON 문자열) → ChatMessageEvent 변환
     */
    private final ObjectMapper objectMapper;

    @Value("${chat.outbox.max-retry-count}")
    private int maxRetryCount;

    // 한 번에 처리할 최대 Outbox 건수 (너무 많으면 스케줄러가 느려짐)
    private static final int BATCH_SIZE = 50;

    /**
     * PENDING Outbox를 읽어 Kafka에 발행하는 스케줄러
     *
     * @Scheduled(fixedDelay):
     *   이전 실행이 완료된 후 delay ms만큼 기다렸다가 다시 실행
     *   (fixedRate와 달리 이전 실행이 끝나야 다음 실행 → 중복 처리 방지)
     */
    @Scheduled(fixedDelayString = "${chat.outbox.scheduler-delay-ms}")
    public void publishPendingOutboxes() {
        // PENDING 상태이고 재시도 가능한 Outbox 최대 BATCH_SIZE건 조회
        List<Outbox> pendingOutboxes = outboxRepository.findPendingOutboxes(
                Outbox.OutboxStatus.PENDING,
                maxRetryCount,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (pendingOutboxes.isEmpty()) {
            return;  // 처리할 이벤트 없음 — 로그 불필요
        }

        log.info("[Outbox 스케줄러] 처리 시작 | 대상: {}건", pendingOutboxes.size());

        for (Outbox outbox : pendingOutboxes) {
            processOutbox(outbox);
        }
    }

    /**
     * Outbox 단건 처리 — JSON 역직렬화 후 Kafka 발행
     *
     * 발행 결과에 따라 상태 업데이트:
     * - 성공: PUBLISHED
     * - 실패: retryCount +1, max 초과 시 FAILED
     *
     * @param outbox 처리할 Outbox 레코드
     */
    @Transactional  // 상태 업데이트를 트랜잭션으로 보장
    public void processOutbox(Outbox outbox) {
        try {
            // ── Step 1: JSON 페이로드 → ChatMessageEvent 역직렬화 ─────
            ChatMessageEvent event = objectMapper.readValue(
                    outbox.getPayload(),
                    ChatMessageEvent.class
            );

            // ── Step 2: Kafka 발행 (비동기) ───────────────────────────
            chatMessageProducer.send(event).whenComplete((result, ex) -> {
                if (ex != null) {
                    // 발행 실패 → retryCount 증가, max 초과 시 FAILED
                    handleFailure(outbox, ex.getMessage());
                } else {
                    // 발행 성공 → PUBLISHED 상태로 변경
                    handleSuccess(outbox);
                }
            });

        } catch (Exception e) {
            // JSON 역직렬화 실패 등 → 재시도 불가 수준의 오류이므로 FAILED 처리
            log.error("[Outbox 스케줄러] payload 처리 실패 | outboxId: {}, 원인: {}",
                    outbox.getOutboxId(), e.getMessage(), e);
            handleFailure(outbox, e.getMessage());
        }
    }

    /**
     * Kafka 발행 성공 처리
     * Outbox 상태를 PUBLISHED로 변경 → 다음 스케줄러 실행 시 조회되지 않음
     */
    @Transactional
    public void handleSuccess(Outbox outbox) {
        log.debug("[Outbox 스케줄러] 발행 성공 | outboxId: {}", outbox.getOutboxId());
        outbox.setStatus(Outbox.OutboxStatus.PUBLISHED);
        outboxRepository.save(outbox);
    }

    /**
     * Kafka 발행 실패 처리
     * retryCount를 1 증가, maxRetryCount 초과 시 FAILED로 전환
     *
     * FAILED 상태:
     * - 더 이상 재시도하지 않음
     * - 모니터링/알림 대상으로 수동 확인 필요
     *
     * @param outbox       실패한 Outbox
     * @param errorMessage 실패 원인
     */
    @Transactional
    public void handleFailure(Outbox outbox, String errorMessage) {
        int newRetryCount = outbox.getRetryCount() + 1;
        outbox.setRetryCount(newRetryCount);

        if (newRetryCount >= maxRetryCount) {
            // 최대 재시도 횟수 초과 → FAILED 처리 (더 이상 재시도 안 함)
            outbox.setStatus(Outbox.OutboxStatus.FAILED);
            log.error("[Outbox 스케줄러] 최대 재시도 초과, FAILED 처리 | outboxId: {}, 시도: {}회, 원인: {}",
                    outbox.getOutboxId(), newRetryCount, errorMessage);
        } else {
            // 재시도 가능 → retryCount만 증가, 다음 스케줄러 실행 시 재처리
            log.warn("[Outbox 스케줄러] 발행 실패, 재시도 예정 | outboxId: {}, 시도: {}회, 원인: {}",
                    outbox.getOutboxId(), newRetryCount, errorMessage);
        }

        outboxRepository.save(outbox);
    }
}
