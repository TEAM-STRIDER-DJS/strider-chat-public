package com.strider.chat.model.request;

import com.strider.chat.model.entity.MessageMedia.MediaType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 메시지 전송 요청 DTO
 *
 * ■ 변경 사항:
 *   이전: senderId + receiverId
 *   이후: roomId + senderId
 *   → receiverId는 이미 ChatRoomMember 테이블에 기록되어 있으므로 제거
 *   → 클라이언트는 먼저 방 생성 API로 roomId를 받은 뒤 이 요청을 보냄
 *
 * ■ STOMP 전송 예시 (클라이언트):
 *   stompClient.publish({
 *     destination: '/pub/chat/send',
 *     body: JSON.stringify({
 *       roomId: 'room-uuid',
 *       senderId: 'user-uuid-1',
 *       content: '안녕하세요!'
 *     })
 *   });
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSendRequest {

    /**
     * 메시지를 보낼 채팅방 ID
     * 방 생성 API(POST /chat/rooms/direct/{receiverId})에서 받은 roomId 사용
     */
    @NotBlank(message = "채팅방 ID는 필수입니다")
    private String roomId;

    /**
     * 메시지 발신자 userId
     * 추후 JWT 토큰에서 서버가 추출하도록 개선 예정
     */
    @Setter
    @NotBlank(message = "발신자 ID는 필수입니다")
    private String senderId;

    /**
     * 메시지 본문 (미디어 전송 시 선택 사항 — media와 content 중 하나는 필수)
     */
    private String content;

    /**
     * 첨부 미디어 목록 (null 또는 빈 리스트이면 텍스트 메시지)
     * 외부 업로드 서비스에서 받은 URL·S3 키를 그대로 전달
     */
    private List<MediaItem> media;

    /**
     * 클라이언트 생성 메시지 ID (UUID)
     * 클라이언트가 전송 시 생성하여 포함 → 서버에서 (roomId, clientMessageId) 중복 체크
     * 네트워크 재전송 등으로 같은 메시지가 중복 도착해도 한 번만 저장
     */
    private String clientMessageId;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MediaItem {
        private MediaType mediaType;
        private String originalUrl;
        private String originalS3Key;
        private String thumbnailUrl;
        private String thumbnailS3Key;
        private String previewUrl;
        private String previewS3Key;
    }
}
