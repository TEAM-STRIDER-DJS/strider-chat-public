package com.strider.chat.model.response;

import com.strider.chat.model.entity.Message;
import com.strider.chat.model.entity.Message.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 메시지 응답 DTO
 *
 * ■ 변경 사항:
 *   receiverId 제거 → roomId 추가
 *   수신자 정보는 ChatRoomMember 테이블에서 관리하므로 메시지 응답에서 제거
 *
 * ■ 역할:
 *   1. REST API 응답 — StriderResponse<MessageResponse>로 감싸서 반환
 *   2. STOMP broadcast — /sub/chat/room/{roomId} 구독자에게 실시간 전송
 *
 * ■ STOMP 수신 예시 (클라이언트):
 *   stompClient.subscribe('/sub/chat/room/{roomId}', (frame) => {
 *     const message = JSON.parse(frame.body); // MessageResponse 구조
 *     console.log(message.content);
 *   });
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageResponse {

    /** 메시지 고유 ID */
    private String messageId;

    /** 메시지가 속한 채팅방 ID */
    private String roomId;

    /** 발신자 userId */
    private String senderId;

    /** 클라이언트 생성 메시지 ID */
    private String clientMessageId;

    /** 메시지 본문 */
    private String content;

    /** 메시지 타입 (TEXT / IMAGE / VIDEO / NOTICE) */
    private MessageType messageType;

    /**
     * NOTICE 말풍선이 가리키는 공지 ID, 그 외 타입은 null
     *
     * 공지 등록 말풍선의 "글 확인하기"가 이동할 공지 상세 페이지 대상.
     * 공지가 삭제됐을 수 있으므로 클라이언트는 이동 실패를 처리해야 한다.
     */
    private String noticeId;

    /** 삭제 여부 */
    private boolean deleted;

    /** 메시지 생성 시각 */
    private LocalDateTime createdAt;

    /** 이 메시지를 아직 읽지 않은 멤버 수 (발신자 제외) */
    private int unreadCount;

    /** broadcast 시 active viewer라 이미 unreadCount에서 제외된 userId 목록 */
    private List<String> preExcludedUserIds;

    /** 첨부 미디어 목록 (sortOrder ASC) */
    private List<MediaResponse> media;

    private static final String DELETED_MESSAGE_CONTENT = "메시지가 삭제되었습니다";

    /**
     * Message 엔티티 → MessageResponse DTO 변환
     * 삭제된 메시지는 content를 "메시지가 삭제되었습니다"로 대체
     *
     * @param message 저장된 Message 엔티티
     * @return 클라이언트에게 전달할 MessageResponse DTO
     */
    public static MessageResponse from(Message message) {
        return from(message, 0, Collections.emptyList());
    }

    public static MessageResponse from(Message message, int unreadCount) {
        return from(message, unreadCount, Collections.emptyList());
    }

    public static MessageResponse from(Message message, int unreadCount, List<MediaResponse> media) {
        return MessageResponse.builder()
                .messageId(message.getMessageId())
                .roomId(message.getRoomId())
                .senderId(message.getSenderId())
                .clientMessageId(message.getClientMessageId())
                .content(message.isDeleted() ? DELETED_MESSAGE_CONTENT : message.getContent())
                .messageType(message.getMessageType())
                .noticeId(message.getNoticeId())
                .deleted(message.isDeleted())
                .createdAt(message.getCreatedAt())
                .unreadCount(unreadCount)
                .media(media)
                .build();
    }
}
