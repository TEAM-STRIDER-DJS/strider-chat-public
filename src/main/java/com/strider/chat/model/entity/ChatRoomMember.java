package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 채팅방 참여자 JPA 엔티티
 *
 * ■ 역할:
 *   어떤 사용자가 어떤 채팅방에 속해 있는지 기록
 *   - DIRECT방: 항상 2명
 *   - 가이드 기준: 다른 서비스(user-profile)를 @ManyToOne 참조하지 않고
 *                 userId(String)만 보관
 *
 * ■ 권한 확인 용도:
 *   메시지 전송 시 "이 사용자가 해당 방의 멤버인지" 확인할 때 사용
 *   Notion 예외 케이스: "멤버 X → 403 에러"
 */
@Entity
@Table(
        name = "chat_room_member",
        uniqueConstraints = {
                // 같은 방에 같은 유저가 중복 등록되지 않도록 보장
                @UniqueConstraint(
                        name = "uq_room_user",
                        columnNames = {"room_id", "user_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomMember {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "member_id", nullable = false, unique = true)
    private String memberId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

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
