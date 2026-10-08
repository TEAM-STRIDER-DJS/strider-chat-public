package com.strider.chat.util;

import com.strider.chat.model.entity.Message.MessageType;

/**
 * 마지막 메시지 미리보기 문구 생성
 *
 * ■ 사용처:
 *   채팅방 목록의 마지막 메시지 줄
 *
 * ■ 규칙:
 *   TEXT   -> 메시지 내용 그대로
 *   IMAGE  -> 1장이면 "사진을 보냈습니다.", 여러 장이면 "사진을 n장 보냈습니다."
 *   VIDEO  -> "동영상을 보냈습니다." (동영상은 항상 1개)
 *   NOTICE -> 메시지 내용 그대로 (= 공지로 걸린 원본 내용)
 *
 * ■ "공지가 등록되었습니다."는 서버가 만들지 않는다:
 *   클라이언트가 messageType == NOTICE 를 보고 UI에서 직접 붙이는 문구다.
 *   채팅방 목록도 lastMessageType으로 같은 판별이 가능하다.
 *
 * ■ messageType을 신뢰할 수 있는 근거:
 *   ChatService.validateMessageComposition()이 한 메시지를 TEXT/IMAGE/VIDEO 중
 *   하나로만 강제하므로, message_media 조인 없이 messageType만으로 판별이 가능하다.
 */
public final class MessagePreviewUtils {

    private MessagePreviewUtils() {}

    /**
     * @param messageType 메시지 유형 (null이면 TEXT로 간주)
     * @param content     메시지 본문 (미디어 메시지는 빈 문자열)
     * @param mediaCount  첨부 미디어 장수 (IMAGE에서만 사용, 그 외는 무시)
     * @return 목록에 그대로 노출할 미리보기 문구
     */
    public static String of(MessageType messageType, String content, int mediaCount) {
        if (messageType == null) {
            return content;
        }
        return switch (messageType) {
            case IMAGE -> mediaCount > 1 ? "사진을 " + mediaCount + "장 보냈습니다." : "사진을 보냈습니다.";
            case VIDEO -> "동영상을 보냈습니다.";
            // NOTICE의 content는 공지로 걸린 원본 내용이라 그대로 내보낸다
            case NOTICE, TEXT -> content;
        };
    }
}
