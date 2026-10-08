package com.strider.chat.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 읽음 처리 broadcast 응답 DTO
 *
 * 클라이언트가 readAt을 기준으로 메시지별 unreadCount를 로컬에서 갱신
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadReceiptResponse {
    private String roomId;
    private String userId;
    private LocalDateTime readAt;
}
