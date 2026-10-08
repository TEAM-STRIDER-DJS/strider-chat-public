package com.strider.chat.client;

import com.strider.chat.model.response.Envelope;
import com.strider.chat.model.response.UserFollowResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * strider-user-profile 서비스 Feign 클라이언트
 *
 * ■ 용도:
 *   사용자 ID 존재 여부 확인 (채팅방 생성 시 상대방 검증)
 *   팔로워 목록 조회 (그룹 채팅방 멤버 초대 시)
 *
 * ■ 인증 헤더:
 *   FeignAuthInterceptorConfig가 자동으로 Authorization 헤더 전파
 */
@FeignClient(name = "userProfileClient", url = "http://strider-user-profile:8001")
// @FeignClient(name = "userProfileClient", url = "http://localhost:8001")
public interface UserProfileClient {

    /**
     * 사용자 ID 존재 여부 확인
     *
     * @param userId 확인할 사용자 ID
     * @return 존재 여부 (true/false)
     */
    @GetMapping("/api/v1/user/profile/{userId}")
    Envelope<Boolean> isExistUser(@PathVariable("userId") String userId);

    /**
     * 특정 사용자의 팔로워 목록 조회
     *
     * @param userId 팔로워를 조회할 사용자 ID
     * @return 팔로워 목록
     */
    @GetMapping("/api/v1/user/profile/{userId}/followers")
    Envelope<List<UserFollowResponse>> getFollowers(@PathVariable("userId") String userId);
}
