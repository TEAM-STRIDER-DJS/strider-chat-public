package com.strider.chat.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 그룹 채팅방 초대 가능 사용자 응답 DTO
 *
 * ■ 사용 시점:
 *   GET /api/v1/chat/rooms/{roomId}/members/invitable
 *
 * ■ alreadyMember:
 *   true → 이미 채팅방 멤버 (클라이언트에서 선택 불가 처리)
 *   false → 초대 가능
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitableUserResponse {

    private String userId;
    private String nickname;
    private String profileImage;
    private String profileThumbImage;
    private boolean alreadyMember;

    public static InvitableUserResponse from(UserFollowResponse follower, boolean alreadyMember) {
        return InvitableUserResponse.builder()
                .userId(follower.userId())
                .nickname(follower.nickname())
                .profileImage(follower.profileImage())
                .profileThumbImage(follower.profileThumbImage())
                .alreadyMember(alreadyMember)
                .build();
    }
}
