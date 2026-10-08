package com.strider.chat.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Push 알림 Kafka 이벤트
 *
 * ■ 발행 조건:
 *   수신자가 오프라인일 때 ChatMessageConsumer에서 발행
 *   → strider-notification 서비스가 소비하여 FCM 전송
 *
 * ■ 토픽: chat.push-notification.event
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushNotificationEvent {

    /** FCM 푸시 수신 대상 userId */
    private String userId;

    /** 메시지가 속한 채팅방 ID */
    private String roomId;

    /** 메시지 발신자 userId */
    private String senderId;

    /** 알림 미리보기 텍스트 (최대 50자) */
    private String messagePreview;

    /** 메시지 타입 (TEXT / IMAGE / VIDEO) */
    private String messageType;

    /** 알림에 띄울 작은 이미지 URL — 사진만 채워지고 동영상·텍스트는 null */
    private String previewImageUrl;
}
