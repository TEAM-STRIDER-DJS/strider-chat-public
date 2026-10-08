package com.strider.chat.repository;

import com.strider.chat.model.entity.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방 참여자 JPA Repository
 *
 * ■ 핵심 메서드:
 *   existsByRoomIdAndUserIdAndIsDeletedFalse — 멤버 권한 확인
 *   → Notion 예외 케이스: "멤버 X → 403 에러"
 */
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, String> {

    /**
     * 특정 방의 특정 사용자가 활성 멤버인지 확인
     *
     * ■ 사용 시점:
     *   메시지 전송 시 senderId가 해당 roomId의 멤버인지 검증
     *   → 멤버가 아니면 StriderException(FORBIDDEN) 발생
     *
     * @param roomId 채팅방 ID
     * @param userId 확인할 사용자 ID
     * @return true → 활성 멤버, false → 멤버 아님 (또는 나감/강퇴)
     */
    boolean existsByRoomIdAndUserIdAndIsDeletedFalse(String roomId, String userId);

    /**
     * 특정 방의 전체 활성 멤버 목록 조회
     *
     * ■ 사용 시점:
     *   방 정보 응답에 참여자 목록 포함 시
     *   DIRECT방이면 항상 2명 반환
     *
     * @param roomId 채팅방 ID
     * @return 삭제되지 않은 활성 멤버 목록
     */
    List<ChatRoomMember> findByRoomIdAndIsDeletedFalse(String roomId);

    /**
     * 특정 방의 특정 사용자 멤버 레코드 조회
     *
     * @param roomId 채팅방 ID
     * @param userId 사용자 ID
     * @return 멤버 레코드 (없으면 Optional.empty())
     */
    Optional<ChatRoomMember> findByRoomIdAndUserIdAndIsDeletedFalse(String roomId, String userId);

    /**
     * 특정 사용자가 속한 모든 활성 채팅방 멤버 레코드 조회
     *
     * ■ 사용 시점:
     *   채팅방 목록 조회 시 사용자가 참여 중인 방 목록 확인
     *
     * @param userId 사용자 ID
     * @return 사용자가 속한 활성 멤버 레코드 목록
     */
    List<ChatRoomMember> findByUserIdAndIsDeletedFalse(String userId);

    /**
     * roomId 목록으로 전체 활성 멤버 일괄 조회
     *
     * @param roomIds 채팅방 ID 목록
     * @return 해당 채팅방들의 활성 멤버 목록
     */
    List<ChatRoomMember> findByRoomIdInAndIsDeletedFalse(List<String> roomIds);

    /**
     * 특정 방의 특정 사용자 멤버 레코드 조회 (삭제 여부 무관)
     *
     * ■ 사용 시점:
     *   퇴장한 사용자 재초대 시 soft-deleted 레코드 재활성화용
     *
     * @param roomId 채팅방 ID
     * @param userId 사용자 ID
     * @return 멤버 레코드 (없으면 Optional.empty())
     */
    Optional<ChatRoomMember> findByRoomIdAndUserId(String roomId, String userId);

    /**
     * 특정 방의 활성 멤버 수 조회
     *
     * ■ 사용 시점:
     *   그룹 채팅방 퇴장 시 마지막 멤버인지 확인하여 방 삭제 판단
     *
     * @param roomId 채팅방 ID
     * @return 활성 멤버 수
     */
    int countByRoomIdAndIsDeletedFalse(String roomId);
}
