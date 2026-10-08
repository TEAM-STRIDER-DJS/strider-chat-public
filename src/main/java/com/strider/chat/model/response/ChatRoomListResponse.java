package com.strider.chat.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 목록 조회 응답 DTO
 *
 * ■ 사용 시점:
 *   GET /api/v1/chat/rooms?userId= 응답
 *   → 클라이언트가 채팅 목록 화면에서 사용
 *
 * ■ 포함 정보:
 *   - 채팅방 기본 정보 (roomId, roomType)
 *   - 상대방 userId (DIRECT 방 기준)
 *   - 마지막 메시지 내용 및 시각
 *   - 안 읽은 메시지 수
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomListResponse {

    /** 채팅방 고유 ID */
    private String roomId;

    /** 채팅방 유형 (DIRECT / GROUP) */
    private String roomType;

    /** 상대방 userId (DIRECT 방 기준, GROUP은 null) */
    private String otherUserId;

    /** 채팅방 이름 (GROUP 방 기준, DIRECT는 null) */
    private String roomName;

    /**
     * 마지막 메시지 미리보기 (메시지 없으면 null)
     *
     * 텍스트는 내용 그대로, 사진·동영상은 "사진을 보냈습니다." 같은 안내 문구가 들어간다.
     * 목록에 그대로 출력하면 된다 (MessagePreviewUtils 참고).
     */
    private String lastMessageContent;

    /** 마지막 메시지 유형 (TEXT / IMAGE / VIDEO, 메시지 없으면 null) */
    private String lastMessageType;

    /** 마지막 메시지 발신자 ID (메시지 없으면 null) */
    private String lastMessageSenderId;

    /** 마지막 메시지 시각 (메시지 없으면 null) */
    private LocalDateTime lastMessageAt;

    /** 안 읽은 메시지 수 */
    private long unreadCount;

    /** 활성 멤버 수 */
    private int memberCount;

    /** 채팅방 생성 시각 */
    private LocalDateTime createdAt;
}
