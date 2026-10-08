package com.strider.chat.model.response;

import lombok.Builder;

/**
 * strider-user-profile 서비스의 팔로워 조회 응답 DTO
 *
 * ■ 용도:
 *   그룹 채팅방 멤버 초대 시 팔로워 목록 조회에 사용
 */
@Builder
public record UserFollowResponse(
        String userId,
        String profileId,
        String nickname,
        String profileImage,
        String profileThumbImage,
        String profileDesc,
        String rankId,
        int postCount,
        int followingCount,
        int followerCount,
        boolean following,
        boolean isFollowed
) {}
