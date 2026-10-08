package com.strider.chat.model.response;

import com.strider.chat.model.entity.ChatRoomMember;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 멤버 응답 DTO
 *
 * ■ 사용 시점:
 *   GET /api/v1/chat/rooms/{roomId}/members 응답
 *   POST /api/v1/chat/rooms/{roomId}/members 응답 (새로 초대된 멤버)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomMemberResponse {

    private String userId;
    private LocalDateTime joinedAt;

    public static ChatRoomMemberResponse from(ChatRoomMember member) {
        return ChatRoomMemberResponse.builder()
                .userId(member.getUserId())
                .joinedAt(member.getCreatedAt())
                .build();
    }
}
