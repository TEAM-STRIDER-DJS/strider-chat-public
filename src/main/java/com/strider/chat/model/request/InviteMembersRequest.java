package com.strider.chat.model.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 그룹 채팅방 멤버 초대 요청 DTO
 *
 * ■ 사용 시점:
 *   POST /api/v1/chat/rooms/{roomId}/members
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InviteMembersRequest {

    @NotEmpty
    private List<String> memberIds;
}
