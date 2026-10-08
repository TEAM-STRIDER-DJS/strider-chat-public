package com.strider.chat.kafka.consumer;

import com.strider.chat.kafka.event.ChatMessageEvent;
import com.strider.chat.kafka.event.ChatMessageEvent.MediaPayload;
import com.strider.chat.kafka.event.PushNotificationEvent;
import com.strider.chat.model.entity.ChatRoomMember;
import com.strider.chat.model.entity.MessageMedia.MediaType;
import com.strider.chat.model.response.MessageResponse;
import com.strider.chat.util.MessagePreviewUtils;
import com.strider.chat.repository.ChatRoomMemberRepository;
import com.strider.chat.service.ChatService;
import com.strider.chat.service.UserPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka 채팅 메시지 이벤트 Consumer
 *
 * ■ 역할:
 *   chat.message.event 토픽을 소비하여 STOMP broadcast 수행
 *   → 채팅방을 구독 중인 클라이언트들에게 실시간으로 메시지 전달
 *
 * ■ 흐름:
 *   Kafka chat.message.event 수신
 *   → ChatMessageEvent → MessageResponse 변환
 *   → SimpMessagingTemplate으로 /sub/chat/room/{roomId} 채널에 push
 *
 * ■ GROUP 채팅 확장 고려:
 *   roomId 기반이므로 DIRECT/GROUP 구분 없이 동일하게 처리
 *   GROUP 채팅 구현 시 이 Consumer 코드 변경 없이 그대로 사용 가능
 *
 * ■ 컨슈머 그룹 (strider-chat-group):
 *   같은 그룹 내 여러 인스턴스가 토픽 파티션을 나눠 처리
 *   → 수평 확장 시 각 인스턴스가 다른 방의 메시지를 병렬 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;
    private final UserPresenceService userPresenceService;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final KafkaTemplate<String, PushNotificationEvent> pushNotificationKafkaTemplate;

    @Value("${chat.kafka.topic.push-notification-event}")
    private String pushNotificationTopic;

    /**
     * chat.message.event 토픽 소비 및 STOMP broadcast
     *
     * ■ @KafkaListener 설정:
     *   - topics: 소비할 Kafka 토픽 이름 (application.yml에서 주입)
     *   - groupId: Consumer 그룹 ID (파티션 분산 처리 단위)
     *   - containerFactory: KafkaConfig에서 등록한 리스너 컨테이너 팩토리
     *
     * ■ 처리 흐름:
     *   1. Kafka에서 ChatMessageEvent 수신
     *   2. MessageResponse DTO로 변환
     *   3. /sub/chat/room/{roomId} 채널로 STOMP push
     *   4. 이 채널을 구독 중인 모든 클라이언트(발신자 포함)가 수신
     *
     * ■ 예외 처리:
     *   @KafkaListener는 예외 발생 시 기본적으로 재시도
     *   심각한 오류는 Dead Letter Topic(DLT)으로 이동 (추후 구현 가능)
     *
     * @param event Kafka에서 소비한 ChatMessageEvent
     */
    @KafkaListener(
            topics = "${chat.kafka.topic.message-event}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ChatMessageEvent event) {
        log.debug("[Kafka Consumer] 이벤트 수신 | messageId: {}, roomId: {}",
                event.getMessageId(), event.getRoomId());

        try {
            // ── Step 1: ChatMessageEvent → MessageResponse 변환 (unreadCount 포함) ──
            MessageResponse response = chatService.buildResponseWithUnread(event);

            // ── Step 2: STOMP로 채팅방 구독자에게 broadcast ───────────
            String destination = "/sub/chat/room/" + event.getRoomId();
            messagingTemplate.convertAndSend(destination, response);

            log.info("[Kafka Consumer] STOMP broadcast 완료 | 채널: {}, messageId: {}",
                    destination, event.getMessageId());

            // ── Step 3: 수신자별 알림 처리 ─────────────────────────────
            List<ChatRoomMember> members = chatRoomMemberRepository
                    .findByRoomIdAndIsDeletedFalse(event.getRoomId());

            for (ChatRoomMember member : members) {
                if (member.getUserId().equals(event.getSenderId())) continue; // 발신자 제외

                String recipientId = member.getUserId();
                boolean online = userPresenceService.isOnline(recipientId);
                String activeRoom = userPresenceService.getActiveRoom(recipientId);
                boolean viewingRoom = event.getRoomId().equals(activeRoom);

                if (!online) {
                    // 오프라인 → Kafka 발행 → strider-notification이 FCM 처리
                    pushNotificationKafkaTemplate.send(pushNotificationTopic,
                            PushNotificationEvent.builder()
                                    .userId(recipientId)
                                    .roomId(event.getRoomId())
                                    .senderId(event.getSenderId())
                                    .messagePreview(resolvePreviewText(event))
                                    .messageType(event.getMessageType() != null ? event.getMessageType().name() : null)
                                    .previewImageUrl(resolvePreviewImageUrl(event))
                                    .build());
                    log.info("[알림] FCM 이벤트 발행 | userId: {}, roomId: {}", recipientId, event.getRoomId());

                } else if (!viewingRoom) {
                    // 온라인 + 다른 방 → STOMP 개인 채널로 인앱 알림 push
                    // Map.of는 null 값을 허용하지 않으므로 미디어가 있을 때만 추가
                    Map<String, Object> notification = new HashMap<>();
                    notification.put("type", "NEW_MESSAGE");
                    notification.put("roomId", event.getRoomId());
                    notification.put("senderId", event.getSenderId());
                    notification.put("preview", resolvePreviewText(event));
                    notification.put("messageType", event.getMessageType());

                    String previewImageUrl = resolvePreviewImageUrl(event);
                    if (previewImageUrl != null) {
                        notification.put("previewImageUrl", previewImageUrl);
                    }

                    messagingTemplate.convertAndSend(
                            "/sub/user/" + recipientId + "/notification", notification);
                    log.info("[알림] 인앱 알림 push | userId: {}, roomId: {}", recipientId, event.getRoomId());

                } else {
                    // 온라인 + 같은 방 → 채팅방 목록 업데이트 알림 (마지막 메시지, 안 읽은 수 갱신)
                    messagingTemplate.convertAndSend(
                            "/sub/user/" + recipientId + "/notification",
                            Map.of(
                                    "type", "ROOM_LIST_UPDATE",
                                    "roomId", event.getRoomId()
                            )
                    );
                    log.debug("[알림] 채팅방 목록 업데이트 알림 | userId: {}, roomId: {}", recipientId, event.getRoomId());
                }
            }

        } catch (Exception e) {
            // Consumer 처리 중 예외 → 로그 기록 후 Kafka가 재시도
            log.error("[Kafka Consumer] 처리 실패 | messageId: {}, 원인: {}",
                    event.getMessageId(), e.getMessage(), e);
            throw e; // Kafka에 실패 전파 → 재시도 또는 DLT로 이동
        }
    }

    /**
     * 알림 미리보기 텍스트
     *
     * 미디어 메시지는 본문이 비어 있으므로 "사진을 n장 보냈습니다." 등의 문구로 대체하고,
     * 텍스트는 50자까지만 싣는다. 장수는 이벤트에 인라인된 미디어 개수로 센다.
     */
    private String resolvePreviewText(ChatMessageEvent event) {
        int mediaCount = event.getMedia() == null ? 0 : event.getMedia().size();
        String preview = MessagePreviewUtils.of(event.getMessageType(), event.getContent(), mediaCount);
        return preview.length() > 50 ? preview.substring(0, 50) + "..." : preview;
    }

    /**
     * 알림에 띄울 작은 이미지 URL (사진 전용)
     *
     * 사진은 첫 장(sortOrder=0)의 썸네일을 대표로 싣고, 동영상은 이미지 없이 문구만 보낸다.
     * 이벤트가 미디어를 인라인으로 싣고 있어 DB 재조회 없이 꺼낼 수 있다.
     *
     * @return 사진이면 첫 장 썸네일 URL, 동영상·텍스트이거나 URL이 없으면 null
     */
    private String resolvePreviewImageUrl(ChatMessageEvent event) {
        if (event.getMedia() == null || event.getMedia().isEmpty()) return null;

        MediaPayload first = event.getMedia().get(0);
        return first.getMediaType() == MediaType.IMAGE
                ? first.getThumbnailUrl()
                : null;
    }
}
