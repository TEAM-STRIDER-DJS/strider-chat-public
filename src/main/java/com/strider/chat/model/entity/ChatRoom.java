package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 채팅방 JPA 엔티티
 *
 * ■ 역할:
 *   - 두 사용자 사이의 1:1(DIRECT) 채팅방을 나타냄
 *   - 채팅방은 최초 메시지 전송 시 생성되거나,
 *     클라이언트가 먼저 방 생성 API를 호출해 만들 수 있음
 *
 * ■ Notion 채팅 프로세스 기준:
 *   "roomId 없이 상대 userId로 보내기 요청 가능
 *    (POST /chat/rooms/direct/{상대userId} → roomId 반환)
 *    DB에서 direct_key UNIQUE로 한 방만 생기게 보장
 *    (이미 있으면 그 방 반환)"
 *
 * ■ directKey 설계:
 *   두 userId를 사전순(lexicographic) 정렬 후 "_" 로 연결
 *   예) userId="user-b", 상대="user-a" → directKey="user-a_user-b"
 *   → 누가 먼저 호출해도 동일한 key 생성 → UNIQUE 제약으로 중복 방지
 */
@Entity
@Table(
        name = "chat_room",
        uniqueConstraints = {
                // direct_key UNIQUE: 두 사용자 사이에 방이 하나만 존재함을 DB 수준에서 보장
                @UniqueConstraint(name = "uq_chat_room_direct_key", columnNames = "direct_key")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoom {

    /**
     * 채팅방 고유 ID (PK)
     * 가이드 규칙: String + GenerationType.UUID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "room_id", nullable = false, unique = true)
    private String roomId;

    /**
     * 채팅방 유형
     * 현재는 DIRECT(1:1)만 구현. 이후 GROUP 확장 예정
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false)
    private RoomType roomType;

    /**
     * DIRECT 방의 고유 키
     * 두 userId를 정렬하여 "_"로 연결한 값
     * 예: "user-aaa_user-bbb"
     *
     * UNIQUE 제약 → 같은 두 사용자 간 방 중복 생성 방지
     * 동시에 두 사람이 방을 만들려 해도 DB 레벨에서 하나만 저장됨
     */
    @Column(name = "direct_key")
    private String directKey;

    /** 채팅방 이름 (GROUP 방에서 사용, DIRECT 방은 null) */
    @Column(name = "room_name")
    private String roomName;

    /**
     * 채팅방 유형 열거형
     * - DIRECT: 1:1 채팅방
     * - GROUP:  그룹 채팅방 (추후 구현)
     */
    public enum RoomType {
        DIRECT,
        GROUP
    }

    // ────────────────── Soft Delete ──────────────────

    /** 논리 삭제 여부 (가이드 규칙: isDeleted + deletedAt 쌍) */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    /** 논리 삭제된 시각 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ────────────────── 타임스탬프 ──────────────────

    /** 방 생성 시각 (자동 설정) */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막 수정 시각 (자동 갱신) */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * directKey 생성 유틸 메서드
     *
     * 두 userId를 사전순 정렬 후 "_" 연결
     * 호출 순서에 관계없이 항상 동일한 key 반환
     *
     * @param userIdA 사용자 A
     * @param userIdB 사용자 B
     * @return 정렬된 directKey (예: "user-a_user-b")
     */
    public static String buildDirectKey(String userIdA, String userIdB) {
        // 사전순(lexicographic)으로 작은 ID가 앞에 오도록 정렬
        if (userIdA.compareTo(userIdB) < 0) {
            return userIdA + "_" + userIdB;
        }
        return userIdB + "_" + userIdA;
    }
}
