package com.strider.chat.model.response;

import com.strider.chat.model.entity.RoomNotice;
import com.strider.chat.model.entity.RoomNotice.EndReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅방 공지 상세 응답 DTO
 *
 * ■ NoticeResponse와 따로 둔 이유:
 *   readCount는 상세 화면에서만 필요한데, 목록(N건)에서 매번 계산하면 방 멤버를
 *   공지 수만큼 훑어야 한다. broadcast payload에도 의미 없는 필드가 섞인다.
 *   상세 전용 DTO로 분리해 목록·broadcast는 가볍게 유지한다.
 *
 * ■ 사용 시점:
 *   GET /api/v1/chat/rooms/{roomId}/notices/{noticeId}
 *   채팅 스트림의 NOTICE 말풍선에서 "글 확인하기"를 눌렀을 때 열리는 화면.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoticeDetailResponse {

    /** 공지 고유 ID */
    private String noticeId;

    /** 공지가 속한 채팅방 ID */
    private String roomId;

    /** 공지로 지정된 원본 메시지 ID (상세 화면에서 원본으로 이동할 때 사용) */
    private String messageId;

    /** 등록 시 채팅 스트림에 남긴 NOTICE 말풍선의 메시지 ID (읽은 사람 수의 기준) */
    private String noticeMessageId;

    /** 공지를 등록한 userId (이 사람만 삭제할 수 있다) */
    private String registeredBy;

    /** 등록 시점 원문 스냅샷 — 공지는 텍스트만 걸 수 있어 원본 메시지 내용 그대로다 */
    private String content;

    /** 내려간 사유 — null이면 현재 걸려 있는 공지 */
    private EndReason endReason;

    /** 공지 등록 시각 */
    private LocalDateTime createdAt;

    /**
     * 공지를 읽은 사람 수 (등록자 제외)
     *
     * ■ 기준: NOTICE 말풍선을 읽은 멤버 수.
     *   공지 상세 화면을 열어본 수가 아니라 채팅을 읽었는지로 센다.
     *
     * ■ 조회 시점 스냅샷이라 실시간으로 갱신되지 않는다.
     *   화면을 다시 열면 그 시점 기준으로 다시 계산된다.
     */
    private int readCount;

    /**
     * RoomNotice 엔티티 + 읽음 수 → 상세 응답 DTO 변환
     *
     * ■ 원본 메시지를 조회하지 않는다:
     *   본문이 공지 행에 스냅샷으로 들어 있고 미디어라는 개념이 없어, 상세 화면도
     *   공지 테이블 단독 조회로 그려진다(읽음 수 계산만 멤버를 훑는다).
     *
     * @param notice    공지 엔티티
     * @param readCount 서비스가 계산한 읽은 사람 수
     * @return 클라이언트에게 전달할 NoticeDetailResponse DTO
     */
    public static NoticeDetailResponse from(RoomNotice notice, int readCount) {
        return NoticeDetailResponse.builder()
                .noticeId(notice.getNoticeId())
                .roomId(notice.getRoomId())
                .messageId(notice.getMessageId())
                .noticeMessageId(notice.getNoticeMessageId())
                .registeredBy(notice.getRegisteredBy())
                .content(notice.getContent())
                .endReason(notice.getEndReason())
                .createdAt(notice.getCreatedAt())
                .readCount(readCount)
                .build();
    }
}
