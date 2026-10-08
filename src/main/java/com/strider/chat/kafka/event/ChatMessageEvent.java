package com.strider.chat.kafka.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.strider.chat.model.entity.Message;
import com.strider.chat.model.entity.Message.MessageType;
import com.strider.chat.model.entity.MessageMedia;
import com.strider.chat.model.entity.MessageMedia.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Kafka 채팅 메시지 이벤트 DTO
 *
 * ■ 역할:
 *   Kafka 토픽(chat.message.event)에 발행되는 이벤트 데이터 구조.
 *   Outbox 테이블에 JSON 직렬화되어 저장되었다가, 스케줄러가 Kafka에 발행.
 *   ChatMessageConsumer가 소비 후 STOMP broadcast.
 *
 * ■ 이벤트 타입:
 *   eventType = "CHAT_MESSAGE_CREATED"
 *   이후 확장: "CHAT_MESSAGE_DELETED", "CHAT_MESSAGE_READ" 등
 *
 * ■ 직렬화:
 *   - Outbox.payload: JSON 문자열로 저장
 *   - Kafka: JSON 형태로 발행/소비 (JsonSerializer/JsonDeserializer)
 *   - LocalDateTime: "yyyy-MM-dd HH:mm:ss" 포맷으로 직렬화
 *
 * ■ GROUP 채팅 확장 고려:
 *   roomId 기반이므로 DIRECT/GROUP 구분 없이 동일하게 처리 가능
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageEvent {

    /** 이벤트 타입 식별자 */
    private ChatEventType eventType;

    /** 메시지 고유 ID */
    private String messageId;

    /** 메시지가 속한 채팅방 ID (DIRECT/GROUP 공통) */
    private String roomId;

    /** 발신자 userId */
    private String senderId;

    /** 메시지 본문 */
    private String content;

    /** 메시지 타입 (TEXT / IMAGE / VIDEO / NOTICE) */
    private MessageType messageType;

    /** NOTICE 말풍선이 가리키는 공지 ID ("글 확인하기" → 공지 상세), 그 외 타입은 null */
    private String noticeId;

    /** 첨부 미디어 목록 (Outbox JSON에 포함되어 컨슈머 DB 재조회 없이 인라인 렌더 가능) */
    private List<MediaPayload> media;

    /** 클라이언트 생성 메시지 ID (낙관적 업데이트 매칭용) */
    private String clientMessageId;

    /** 메시지 생성 시각 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static ChatMessageEvent from(Message message) {
        return from(message, Collections.emptyList());
    }

    /** 저장된 MessageMedia 목록을 MediaPayload로 변환해 이벤트에 인라인 */
    public static ChatMessageEvent from(Message message, List<MessageMedia> mediaList) {
        List<MediaPayload> payloads = mediaList.stream()
                .map(m -> MediaPayload.builder()
                        .mediaId(m.getMediaId())
                        .mediaType(m.getMediaType())
                        .originalUrl(m.getOriginalUrl())
                        .originalS3Key(m.getOriginalS3Key())
                        .thumbnailUrl(m.getThumbnailUrl())
                        .thumbnailS3Key(m.getThumbnailS3Key())
                        .previewUrl(m.getPreviewUrl())
                        .previewS3Key(m.getPreviewS3Key())
                        .sortOrder(m.getSortOrder())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();

        return ChatMessageEvent.builder()
                .eventType(ChatEventType.CHAT_MESSAGE_CREATED)
                .messageId(message.getMessageId())
                .roomId(message.getRoomId())
                .senderId(message.getSenderId())
                .content(message.getContent())
                .messageType(message.getMessageType())
                .noticeId(message.getNoticeId())
                .media(payloads)
                .clientMessageId(message.getClientMessageId())
                .createdAt(message.getCreatedAt())
                .build();
    }

    /** 미디어 한 건의 전달용 정보 (MessageMedia 엔티티에서 전달에 필요한 필드만 추림) */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MediaPayload {
        private String mediaId;
        private MediaType mediaType;
        private String originalUrl;
        private String originalS3Key;
        private String thumbnailUrl;
        private String thumbnailS3Key;
        private String previewUrl;
        private String previewS3Key;
        private int sortOrder;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createdAt;
    }
}
