package com.strider.chat.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 안 읽은 메시지 기준 양방향 조회 응답 DTO
 *
 * ■ 사용 시점:
 *   GET /api/v1/chat/rooms/{roomId}/messages/unread 응답
 *   → 채팅방 입장 시 lastReadAt 기준으로 이전 메시지(컨텍스트) + 안 읽은 메시지 반환
 *
 * ■ 클라이언트 활용:
 *   lastReadMessageId를 기준으로 "여기부터 안 읽은 메시지" 구분선 렌더링
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnreadMessagesResponse {

    /** 시간순 정렬된 메시지 목록 (이전 메시지 + 안 읽은 메시지) */
    private List<MessageResponse> messages;

    /** 안 읽은 메시지 수 */
    private int unreadCount;

    /** 마지막으로 읽은 메시지 ID (구분선 렌더링용, nullable) */
    private String lastReadMessageId;
}
