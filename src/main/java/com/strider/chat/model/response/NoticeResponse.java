package com.strider.chat.model.response;

import com.strider.chat.model.entity.RoomNotice;
import com.strider.chat.model.entity.RoomNotice.EndReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 공지 응답 DTO
 *
 * ■ 역할:
 *   1. REST API 응답 — 등록/활성 조회/목록 조회
 *   2. STOMP broadcast — /sub/chat/room/{roomId}/notice 구독자에게 실시간 전송
 *
 * ■ endReason 으로 상태를 판별한다:
 *   null         현재 걸려 있는 공지
 *   REPLACED     새 공지로 교체되어 내려간 공지
 *   UNREGISTERED 사용자가 내린 공지 (목록에는 그대로 남는다)
 *   DELETED      삭제되면서 함께 내려간 공지
 *
 * ■ broadcast payload 구분법 (/sub/chat/room/{roomId}/notice):
 *   noticeId != null, endReason == null  → 새 공지 등록. 배너를 이걸로 교체
 *   noticeId == null                     → 공지 내림. 배너를 숨김
 *   noticeId != null, endReason DELETED  → 공지 삭제. 목록에서 제거하고,
 *                                          현재 배너의 noticeId와 같으면 배너도 숨김
 *
 * ■ 공지는 텍스트만 걸 수 있다:
 *   사진/동영상은 공지 대상이 아니므로 배너에 띄울 미디어도, 원본 유형을 구분할
 *   필드도 없다. content가 곧 원문이라 배너는 이 값을 그대로 한 줄로 보여주면 된다.
 *   "공지가 등록되었습니다." 같은 머리말은 서버가 만들지 않는다 — 클라이언트가
 *   붙이는 문구라 바뀌어도 서버 배포가 필요 없다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoticeResponse {

    /** 공지 고유 ID (내리기 broadcast에서는 null) */
    private String noticeId;

    /** 공지가 속한 채팅방 ID */
    private String roomId;

    /** 공지로 지정된 메시지 ID (내리기·삭제 broadcast에서는 null) */
    private String messageId;

    /** 공지를 등록한 userId (내리기·삭제 broadcast에서는 null) */
    private String registeredBy;

    /**
     * 등록 시점 원문 스냅샷 (내리기·삭제 broadcast에서는 null)
     *
     * 텍스트 공지만 존재하므로 원본 메시지 내용 그대로다 — 배너에 그대로 쓰면 된다.
     */
    private String content;

    /** 삭제 여부 — true면 공지 목록에서도 제외된다 (내려간 것과는 별개) */
    private boolean deleted;

    /** 내려간 사유 — null이면 현재 활성 공지 */
    private EndReason endReason;

    /** 공지 등록 시각 */
    private LocalDateTime createdAt;

    /**
     * RoomNotice 엔티티 → NoticeResponse DTO 변환
     *
     * ■ 원본 메시지를 인자로 받지 않는다:
     *   표시 문구가 공지 행 자체에 스냅샷으로 들어 있어 message 조회가 필요 없다.
     *
     * @param notice 공지 엔티티
     * @return 클라이언트에게 전달할 NoticeResponse DTO
     */
    public static NoticeResponse from(RoomNotice notice) {
        return NoticeResponse.builder()
                .noticeId(notice.getNoticeId())
                .roomId(notice.getRoomId())
                .messageId(notice.getMessageId())
                .registeredBy(notice.getRegisteredBy())
                .content(notice.getContent())
                .deleted(notice.isDeleted())
                .endReason(notice.getEndReason())
                .createdAt(notice.getCreatedAt())
                .build();
    }

    /**
     * 공지 내리기 broadcast payload
     *
     * ■ noticeId / messageId를 null로 두어 클라이언트 스키마를 단일화한다.
     *   구독자는 noticeId == null 이면 공지 배너를 내리면 된다.
     *   공지 자체는 목록에 남아 있으므로 목록에서는 지우면 안 된다.
     *
     * @param roomId 공지가 내려간 채팅방 ID
     * @return 내리기를 알리는 NoticeResponse
     */
    public static NoticeResponse cleared(String roomId) {
        return NoticeResponse.builder()
                .roomId(roomId)
                .deleted(false)
                .endReason(EndReason.UNREGISTERED)
                .build();
    }

    /**
     * 공지 삭제 broadcast payload
     *
     * ■ 내리기와 달리 noticeId를 싣는다:
     *   구독자는 이 noticeId를 공지 목록에서 제거하고, 그것이 현재 배너에 떠 있는
     *   공지라면 배너도 함께 내린다. 활성 공지였는지를 서버가 따로 알려주지 않아도
     *   클라이언트가 자기 배너의 noticeId와 비교해 판단할 수 있다.
     *
     * @param roomId   공지가 삭제된 채팅방 ID
     * @param noticeId 삭제된 공지 ID
     * @return 삭제를 알리는 NoticeResponse
     */
    public static NoticeResponse removed(String roomId, String noticeId) {
        return NoticeResponse.builder()
                .roomId(roomId)
                .noticeId(noticeId)
                .deleted(true)
                .endReason(EndReason.DELETED)
                .build();
    }
}
