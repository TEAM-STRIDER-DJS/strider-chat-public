package com.strider.chat.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "message_media",
        indexes = {
                @Index(name = "idx_message_media_message", columnList = "message_id"),
                @Index(name = "idx_message_media_gallery", columnList = "room_id, created_at, media_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageMedia {

    public enum MediaType {
        IMAGE, VIDEO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "media_id", nullable = false, unique = true)
    private String mediaId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 8)
    private MediaType mediaType;

    @Column(name = "original_url", nullable = false, columnDefinition = "TEXT")
    private String originalUrl;

    @Column(name = "original_s3_key", nullable = false)
    private String originalS3Key;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "thumbnail_s3_key")
    private String thumbnailS3Key;

    @Column(name = "preview_url", columnDefinition = "TEXT")
    private String previewUrl;

    @Column(name = "preview_s3_key")
    private String previewS3Key;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
