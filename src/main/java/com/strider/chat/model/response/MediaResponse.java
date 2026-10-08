package com.strider.chat.model.response;

import com.strider.chat.model.entity.MessageMedia;
import com.strider.chat.model.entity.MessageMedia.MediaType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaResponse {

    private String mediaId;
    private String messageId;
    private String senderId;
    private MediaType mediaType;
    private String originalUrl;
    private String originalS3Key;
    private String thumbnailUrl;
    private String thumbnailS3Key;
    private String previewUrl;
    private String previewS3Key;
    private int sortOrder;
    private LocalDateTime createdAt;

    public static MediaResponse from(MessageMedia media) {
        return MediaResponse.builder()
                .mediaId(media.getMediaId())
                .messageId(media.getMessageId())
                .senderId(media.getSenderId())
                .mediaType(media.getMediaType())
                .originalUrl(media.getOriginalUrl())
                .originalS3Key(media.getOriginalS3Key())
                .thumbnailUrl(media.getThumbnailUrl())
                .thumbnailS3Key(media.getThumbnailS3Key())
                .previewUrl(media.getPreviewUrl())
                .previewS3Key(media.getPreviewS3Key())
                .sortOrder(media.getSortOrder())
                .createdAt(media.getCreatedAt())
                .build();
    }
}
