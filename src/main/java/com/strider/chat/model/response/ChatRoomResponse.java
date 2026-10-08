package com.strider.chat.model.response;

import com.strider.chat.model.entity.ChatRoom;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 응답 DTO
 *
 * ■ 사용 시점:
 *   POST /api/v1/chat/rooms/direct/{receiverId} 응답
 *   → 클라이언트가 이 roomId로 STOMP 구독 및 메시지 전송
 *
 * ■ 클라이언트 활용 흐름:
 *   1. API 호출 → ChatRoomResponse.roomId 수신
 *   2. STOMP 구독: /sub/chat/room/{roomId}
 *   3. STOMP 전송: /pub/chat/send (roomId + senderId + content 포함)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomResponse {

    /** 채팅방 고유 ID — 이후 STOMP 구독/전송에 사용 */
    private String roomId;

    /** 채팅방 유형 (DIRECT 또는 GROUP) */
    private String roomType;

    /** 채팅방 이름 (GROUP방에서 사용, DIRECT방은 null) */
    private String roomName;

    /** 채팅방 생성 시각 */
    private LocalDateTime createdAt;

    /** 신규 생성 여부 — true: 새로 만들어진 방, false: 기존에 이미 존재하던 방 */
    private boolean isNew;

    /**
     * ChatRoom 엔티티 → ChatRoomResponse DTO 변환
     *
     * @param room  ChatRoom 엔티티
     * @param isNew 신규 생성 여부
     * @return ChatRoomResponse DTO
     */
    public static ChatRoomResponse from(ChatRoom room, boolean isNew) {
        return ChatRoomResponse.builder()
                .roomId(room.getRoomId())
                .roomType(room.getRoomType().name())
                .roomName(room.getRoomName())
                .createdAt(room.getCreatedAt())
                .isNew(isNew)
                .build();
    }
}
