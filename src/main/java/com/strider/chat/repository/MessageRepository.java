package com.strider.chat.repository;

import com.strider.chat.model.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 메시지 JPA Repository
 *
 * Strider MSA 개발 가이드 8번 항목 "Repository 작성 규칙"을 따릅니다.
 *
 * ■ 변경 사항:
 *   senderId/receiverId 기반 쿼리 → roomId 기반 쿼리로 전환
 *   → 하나의 roomId로 채팅방 내 모든 메시지 조회
 */
public interface MessageRepository extends JpaRepository<Message, String> {

    /**
     * clientMessageId 기반 중복 메시지 조회
     * (roomId, clientMessageId) 조합으로 이미 저장된 메시지가 있는지 확인
     */
    Optional<Message> findByRoomIdAndClientMessageIdAndIsDeletedFalse(String roomId, String clientMessageId);

    /**
     * 메시지 단건 조회 (삭제된 메시지 제외)
     * 가이드 규칙: findBy{PK}AndIsDeletedFalse 패턴
     *
     * @param messageId 조회할 메시지 ID
     * @return 삭제되지 않은 메시지
     */
    Optional<Message> findByMessageIdAndIsDeletedFalse(String messageId);

    /**
     * 채팅방 메시지 목록 조회 — 최초 조회 (커서 없음)
     *
     * ■ 사용 시점:
     *   채팅방 입장 시 최신 메시지 N개 로드 (커서 파라미터 없이 호출)
     *
     * @param roomId   채팅방 ID
     * @param pageable 페이지 크기
     * @return 해당 방의 최신 메시지 목록 (최신순 정렬)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND m.isDeleted = false
            ORDER BY m.createdAt DESC, m.messageId DESC
            """)
    List<Message> findMessagesFirst(
            @Param("roomId") String roomId,
            Pageable pageable
    );

    /**
     * 채팅방 메시지 목록 조회 — 커서 이후 (무한 스크롤)
     *
     * 가이드 규칙 8-2: 이전 마지막 메시지의 커서 기준으로 이전 데이터 조회
     *
     * ■ 사용 시점:
     *   채팅방 위로 스크롤 시 이전 메시지 더 불러오기
     *
     * @param roomId          채팅방 ID
     * @param cursorCreatedAt 이전 페이지 마지막 메시지 createdAt
     * @param cursorId        이전 페이지 마지막 메시지 messageId
     * @param pageable        페이지 크기
     * @return 커서 이전의 메시지 목록 (최신순 정렬)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND (
                  m.createdAt < :cursorCreatedAt
                  OR (m.createdAt = :cursorCreatedAt AND m.messageId < :cursorId)
              )
            ORDER BY m.createdAt DESC, m.messageId DESC
            """)
    List<Message> findMessagesAfterCursor(
            @Param("roomId") String roomId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * 채팅방의 마지막 메시지 조회
     *
     * ■ 사용 시점:
     *   채팅방 목록 조회 시 각 방의 마지막 메시지 미리보기 표시
     *
     * @param roomId 채팅방 ID
     * @return 가장 최근 메시지 (없으면 Optional.empty())
     */
    Optional<Message> findTopByRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(String roomId);

    /**
     * 특정 채팅방에서 특정 시각 이후의 안 읽은 메시지 수 조회
     *
     * @param roomId     채팅방 ID
     * @param userId     사용자 ID (본인 발신 제외)
     * @param lastReadAt 마지막 읽은 시각
     * @return 안 읽은 메시지 수
     */
    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.roomId = :roomId
              AND m.senderId <> :userId
              AND m.isDeleted = false
              AND m.createdAt > :lastReadAt
            """)
    long countUnreadMessages(
            @Param("roomId") String roomId,
            @Param("userId") String userId,
            @Param("lastReadAt") LocalDateTime lastReadAt
    );

    /**
     * 특정 채팅방에서 안 읽은 메시지 수 조회 (한 번도 읽지 않은 경우 — 전체 메시지 대상)
     *
     * @param roomId 채팅방 ID
     * @param userId 사용자 ID (본인 발신 제외)
     * @return 안 읽은 메시지 수
     */
    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.roomId = :roomId
              AND m.senderId <> :userId
              AND m.isDeleted = false
            """)
    long countAllUnreadMessages(
            @Param("roomId") String roomId,
            @Param("userId") String userId
    );

    /**
     * 기준 시점 이전 & 참여 이후 메시지 조회 (컨텍스트용)
     *
     * ■ 사용 시점:
     *   채팅방 입장 시 안 읽은 메시지 위에 보여줄 이전 메시지 로드
     *   joinedAt 이전 메시지는 제외하여 초대 전 대화가 노출되지 않도록 함
     *
     * @param roomId   채팅방 ID
     * @param pivot    기준 시점 (lastReadAt 또는 member.createdAt)
     * @param joinedAt 멤버 참여 시점 (ChatRoomMember.createdAt)
     * @param pageable 페이지 크기
     * @return 기준 시점 이전 메시지 목록 (최신순 정렬)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND m.isDeleted = false
              AND m.createdAt <= :pivot
              AND m.createdAt >= :joinedAt
            ORDER BY m.createdAt DESC, m.messageId DESC
            """)
    List<Message> findMessagesBefore(
            @Param("roomId") String roomId,
            @Param("pivot") LocalDateTime pivot,
            @Param("joinedAt") LocalDateTime joinedAt,
            Pageable pageable
    );

    /**
     * 기준 시점 이후 메시지 조회 (안 읽은 메시지)
     *
     * ■ 사용 시점:
     *   채팅방 입장 시 안 읽은 메시지 로드
     *
     * @param roomId 채팅방 ID
     * @param pivot  기준 시점 (lastReadAt 또는 member.createdAt)
     * @param pageable 페이지 크기
     * @return 기준 시점 이후 메시지 목록 (오래된 순 정렬)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND m.isDeleted = false
              AND m.createdAt > :pivot
            ORDER BY m.createdAt ASC, m.messageId ASC
            """)
    List<Message> findMessagesAfter(
            @Param("roomId") String roomId,
            @Param("pivot") LocalDateTime pivot,
            Pageable pageable
    );

    /**
     * 커서 기준 이후 메시지 조회 (아래로 스크롤)
     *
     * ■ 사용 시점:
     *   채팅방에서 아래로 스크롤하여 더 새로운 메시지 로드
     *
     * @param roomId          채팅방 ID
     * @param cursorCreatedAt 이전 페이지 마지막 메시지 createdAt
     * @param cursorId        이전 페이지 마지막 메시지 messageId
     * @param pageable        페이지 크기
     * @return 커서 이후 메시지 목록 (오래된 순 정렬)
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.roomId = :roomId
              AND m.isDeleted = false
              AND (
                  m.createdAt > :cursorCreatedAt
                  OR (m.createdAt = :cursorCreatedAt AND m.messageId > :cursorId)
              )
            ORDER BY m.createdAt ASC, m.messageId ASC
            """)
    List<Message> findMessagesByCursorAfter(
            @Param("roomId") String roomId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );

    /**
     * 여러 채팅방의 마지막 메시지 일괄 조회
     * 서브쿼리로 각 방의 최신 메시지 createdAt을 구한 뒤 매칭
     *
     * @param roomIds 채팅방 ID 목록
     * @return 각 방의 마지막 메시지 목록
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.isDeleted = false
              AND m.roomId IN :roomIds
              AND m.createdAt = (
                  SELECT MAX(m2.createdAt) FROM Message m2
                  WHERE m2.roomId = m.roomId AND m2.isDeleted = false
              )
            """)
    List<Message> findLastMessagesByRoomIdIn(@Param("roomIds") List<String> roomIds);

    /**
     * 여러 채팅방의 안 읽은 메시지 수 일괄 조회
     * chat_room_member의 last_read_at을 기준으로 계산
     * last_read_at이 NULL이면 전체 메시지를 안 읽은 것으로 처리
     *
     * @param roomIds 채팅방 ID 목록
     * @param userId  사용자 ID
     * @return [room_id, unread_count] 배열 목록
     */
    @Query(value = """
            SELECT m.room_id, COUNT(m.message_id)
            FROM message m
            JOIN chat_room_member crm ON crm.room_id = m.room_id
                AND crm.user_id = :userId AND crm.is_deleted = false
            WHERE m.room_id IN :roomIds
              AND m.is_deleted = false
              AND m.sender_id <> :userId
              AND (crm.last_read_at IS NULL OR m.created_at > crm.last_read_at)
            GROUP BY m.room_id
            """, nativeQuery = true)
    List<Object[]> countUnreadMessagesByRoomIdIn(
            @Param("roomIds") List<String> roomIds,
            @Param("userId") String userId
    );
}
