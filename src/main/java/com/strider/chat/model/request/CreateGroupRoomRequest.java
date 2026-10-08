package com.strider.chat.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 그룹 채팅방 생성 요청 DTO
 *
 * ■ 사용 시점:
 *   POST /api/v1/chat/rooms/group
 *
 * ■ 제약:
 *   - roomName 필수
 *   - memberIds에 본인 제외 최소 1명 이상 (본인 포함 최소 2명)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateGroupRoomRequest {

    @NotBlank
    private String roomName;

    @NotEmpty
    private List<String> memberIds;
}