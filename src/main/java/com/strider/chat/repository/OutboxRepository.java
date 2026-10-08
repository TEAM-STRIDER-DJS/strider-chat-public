package com.strider.chat.repository;

import com.strider.chat.model.entity.Outbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Outbox JPA Repository
 *
 * ■ 핵심 메서드:
 *   findPendingOutboxes — OutboxScheduler가 주기적으로 호출하여 미전달 이벤트 조회
 */
public interface OutboxRepository extends JpaRepository<Outbox, String> {

    /**
     * PENDING 상태의 Outbox 조회 (재시도 횟수 제한 포함)
     *
     * ■ 사용 시점:
     *   OutboxScheduler가 5초마다 호출하여 Kafka에 발행할 이벤트 목록 조회
     *
     * ■ retryCount 조건:
     *   maxRetryCount 미만인 것만 조회
     *   → 최대 재시도 횟수를 초과한 이벤트는 FAILED 처리 후 더 이상 조회하지 않음
     *
     * ■ 배치 처리:
     *   한 번에 너무 많이 처리하면 스케줄러가 느려지므로 Pageable로 건수 제한
     *
     * @param maxRetryCount 최대 재시도 횟수 (application.yml: max-retry-count)
     * @param pageable      한 번에 처리할 최대 건수
     * @return PENDING 상태이고 재시도 가능한 Outbox 목록
     */
    @Query("""
            SELECT o FROM Outbox o
            WHERE o.status = :status
              AND o.retryCount < :maxRetryCount
            ORDER BY o.createdAt ASC
            """)
    List<Outbox> findPendingOutboxes(
            @Param("status") Outbox.OutboxStatus status,
            @Param("maxRetryCount") int maxRetryCount,
            Pageable pageable
    );
}
