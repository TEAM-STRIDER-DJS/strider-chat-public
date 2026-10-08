package com.strider.chat.repository;

import com.strider.chat.model.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방 JPA Repository
 *
 * ■ 핵심 메서드:
 *   findByDirectKeyAndIsDeletedFalse — 기존 방 존재 여부 확인
 *   → Notion 프로세스: "이미 있으면 그 방 반환"
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, String> {

    /**
     * directKey로 채팅방 조회 (삭제되지 않은 방만)
     *
     * ■ 사용 시점:
     *   POST /chat/rooms/direct/{receiverId} 호출 시
     *   → 이미 존재하는 방인지 먼저 확인
     *   → 있으면 그대로 반환, 없으면 새로 생성
     *
     * @param directKey "userA_userB" 형식의 고유 키 (사전순 정렬)
     * @return 존재하는 채팅방 (없으면 Optional.empty())
     */
    Optional<ChatRoom> findByDirectKeyAndIsDeletedFalse(String directKey);

    /**
     * roomId로 채팅방 단건 조회 (삭제되지 않은 방만)
     * 가이드 규칙: findBy{PK}AndIsDeletedFalse 패턴
     *
     * @param roomId 채팅방 고유 ID
     * @return 삭제되지 않은 채팅방
     */
    Optional<ChatRoom> findByRoomIdAndIsDeletedFalse(String roomId);

    /**
     * roomId 목록으로 채팅방 일괄 조회 (삭제되지 않은 방만)
     *
     * @param roomIds 채팅방 ID 목록
     * @return 삭제되지 않은 채팅방 목록
     */
    List<ChatRoom> findByRoomIdInAndIsDeletedFalse(List<String> roomIds);
}
