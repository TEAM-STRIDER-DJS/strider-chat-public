package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 채팅 메시지 JPA 엔티티
 */
@Entity
@Table(
        name = "message",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_room_client_message",
                        columnNames = {"room_id", "client_message_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    /**
     * 메시지 유형
     *
     * TEXT/IMAGE/VIDEO 는 사용자가 보낸 메시지이고,
     * NOTICE 는 공지 등록 시 서버가 채팅 스트림에 남기는 시스템 메시지다.
     * (사용자 전송 경로에서는 media 구성으로만 타입이 결정되므로 NOTICE가 만들어질 수 없다)
     */
    public enum MessageType {
        TEXT, IMAGE, VIDEO, NOTICE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "client_message_id")
    private String clientMessageId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    // columnDefinition의 default 'TEXT'는 ddl-auto=update 시 기존 행 backfill 안전망
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 16,
            columnDefinition = "varchar(16) default 'TEXT'")
    @Builder.Default
    private MessageType messageType = MessageType.TEXT;

    /**
     * 이 말풍선이 가리키는 공지 ID — NOTICE 전용, 그 외 타입은 null
     *
     * 공지 등록 알림 말풍선의 "글 확인하기"가 이동할 공지 상세 페이지 대상이다.
     * 원본 메시지가 아니라 공지를 가리킨다 — 원본으로 가는 링크는 공지 상세에 있다.
     * 공지가 삭제될 수 있으므로 FK를 걸지 않는다(말풍선은 대화 기록이라 그대로 남는다).
     */
    @Column(name = "notice_id")
    private String noticeId;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
