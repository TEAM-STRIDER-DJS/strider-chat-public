package com.strider.chat.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * STOMP 읽음 처리 요청 DTO
 *
 * ■ STOMP 전송 예시 (클라이언트):
 *   stompClient.send('/pub/chat/read', {}, JSON.stringify({
 *     roomId: 'room-uuid',
 *     userId: 'user-uuid'
 *   }));
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadReceiptRequest {
    private String roomId;
    private String userId;
}
