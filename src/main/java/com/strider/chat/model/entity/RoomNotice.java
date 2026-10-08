package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 채팅방 공지 JPA 엔티티
 *
 * ■ 방당 활성 공지 1개:
 *   새 공지를 등록하면 기존 활성 행을 내리고 신규 행을 넣는다.
 *   DB 부분 유니크 인덱스는 ddl-auto가 만들지 못하므로 NoticeService의
 *   @Transactional 안에서 보장한다.
 *
 * ■ "내려감"과 "삭제"는 서로 독립된 상태다:
 *   내려감(endReason)  배너에서 내려왔다. 공지 목록에는 그대로 남는다.
 *   삭제(isDeleted)    목록에서도 지운다. 활성 공지를 삭제하면 함께 내려간다.
 *
 *   따라서 한 행이 가질 수 있는 상태는 다음 세 가지다.
 *     endReason == null, isDeleted == false  → 지금 걸려 있는 공지 (배너)
 *     endReason != null, isDeleted == false  → 내려갔지만 목록에는 남는 공지
 *     isDeleted == true                      → 삭제되어 어디에도 안 보이는 공지 (감사 이력만)
 *
 *   두 상태를 한 필드로 합치면 "내리기"와 "삭제"를 구분할 수 없어 나눠 두었다.
 *
 * ■ 텍스트 메시지만 공지로 걸 수 있다:
 *   사진/동영상은 공지 대상이 아니다(NoticeService.registerNotice에서 차단).
 *   그래서 공지가 참조하는 미디어라는 개념이 없고, S3 보관 기간·삭제 정책이
 *   공지 때문에 달라질 일도 없다.
 *
 * ■ 본문 스냅샷:
 *   공지 문구는 등록 시점에 content로 복사해 둔다.
 *   원본 메시지가 삭제돼도 공지는 그대로 남아야 하므로, 읽기 경로가 message 테이블에
 *   의존하지 않게 만든 것이다.
 *   messageId는 "원본으로 이동" 용도로만 남는다(삭제됐으면 이동만 실패).
 */
@Entity
@Table(
        name = "room_notice",
        indexes = {
                // 활성 공지 조회(room_id + end_reason IS NULL) / 목록 조회(room_id + is_deleted) 겸용
                @Index(name = "idx_room_notice_active", columnList = "room_id, end_reason, is_deleted"),
                @Index(name = "idx_room_notice_history", columnList = "room_id, created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomNotice {
    /**
     * 공지가 배너에서 내려간 사유
     *
     * REPLACED     새 공지로 교체됨
     * UNREGISTERED 사용자가 공지를 내림 (목록에는 남음)
     * DELETED      활성 공지가 삭제되면서 함께 내려감
     */
    public enum EndReason {
        REPLACED, UNREGISTERED, DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notice_id", nullable = false, unique = true)
    private String noticeId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    /**
     * 공지로 지정된 메시지 — 원본으로 이동하기 위한 참조
     *
     * 이 메시지가 삭제돼도 공지 행은 남는다. 표시 문구는 content 스냅샷을 쓰므로
     * 여기서 message를 다시 읽을 필요가 없다.
     */
    @Column(name = "message_id", nullable = false)
    private String messageId;

    /**
     * 등록 시 채팅 스트림에 남긴 "공지가 등록되었습니다." 말풍선의 메시지 ID
     *
     * ■ messageId(원본 메시지)와는 다른 메시지다:
     *   messageId       사용자가 공지로 지정한 원본 메시지
     *   noticeMessageId 등록 시 서버가 만든 NOTICE 시스템 메시지
     *
     * ■ 읽은 사람 수의 기준:
     *   공지 상세의 "읽은 사람 수"는 이 말풍선을 읽은 멤버 수로 센다.
     *   이 값이 없으면 셀 대상을 특정할 수 없어 읽음 수가 0으로 나온다.
     */
    @Column(name = "notice_message_id")
    private String noticeMessageId;

    /**
     * 등록 시점에 복사해 둔 공지 원문 (원본 메시지가 삭제·수정돼도 유지)
     *
     * 텍스트 메시지만 공지로 걸 수 있으므로 원본 메시지의 content 그대로다.
     * 길이 제한을 두지 않는 이유는 원본 message.content가 TEXT라 같은 값이 들어오기
     * 때문이다 — varchar(255)로 두면 긴 메시지를 공지로 걸 때 저장이 실패한다.
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 공지를 등록한 userId */
    @Column(name = "registered_by", nullable = false)
    private String registeredBy;

    /**
     * 배너에서 내려간 사유 — null이면 지금 걸려 있는 공지
     *
     * 불변식: endReason == null ⟺ endedAt == null
     * 두 필드를 항상 함께 세팅하도록 NoticeService.endNotice(...)에서만 변경한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 16)
    private EndReason endReason;

    /** 배너에서 내려간 시각 */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    /**
     * 삭제 여부 — true면 공지 목록에서도 제외된다
     *
     * 불변식: isDeleted == true ⟹ endReason != null
     * (활성 공지를 삭제하면 DELETED 사유로 함께 내려가므로 삭제된 활성 공지는 존재하지 않는다)
     */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    /** 공지가 삭제된 시각 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
