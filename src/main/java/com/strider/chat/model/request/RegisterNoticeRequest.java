package com.strider.chat.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅방 공지 등록 요청 DTO
 *
 * ■ 사용 시점:
 *   POST /api/v1/chat/rooms/{roomId}/notices
 *
 * ■ roomId는 경로 변수, userId는 JWT에서 추출하므로 body에는 messageId만 담는다.
 *   (컨트롤러에서 @Valid를 쓰지 않는 코드베이스라 검증은 NoticeService에서 한다)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterNoticeRequest {

    /** 공지로 등록할 메시지 ID */
    private String messageId;
}
