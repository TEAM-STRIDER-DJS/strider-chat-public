package com.strider.chat.repository;

import com.strider.chat.model.entity.MessageMedia;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageMediaRepository extends JpaRepository<MessageMedia, String> {

    List<MessageMedia> findByMessageIdAndIsDeletedFalseOrderBySortOrderAsc(String messageId);

    List<MessageMedia> findByMessageIdInAndIsDeletedFalseOrderBySortOrderAsc(List<String> messageIds);

    /**
     * 메시지별 미디어 장수 일괄 조회 (채팅방 목록의 "사진을 n장 보냈습니다" 미리보기용)
     * 반환: Object[]{ messageId(String), count(Long) }
     */
    @Query("""
            SELECT mm.messageId, COUNT(mm)
            FROM MessageMedia mm
            WHERE mm.messageId IN :messageIds AND mm.isDeleted = false
            GROUP BY mm.messageId
            """)
    List<Object[]> countByMessageIdIn(@Param("messageIds") List<String> messageIds);

    // Phase 2 (갤러리) 에서 추가될 커서 쿼리
    @Query("""
            SELECT mm FROM MessageMedia mm
            WHERE mm.roomId = :roomId AND mm.isDeleted = false
            ORDER BY mm.createdAt DESC, mm.mediaId DESC
            """)
    List<MessageMedia> findGalleryFirst(@Param("roomId") String roomId, Pageable pageable);

    @Query("""
            SELECT mm FROM MessageMedia mm
            WHERE mm.roomId = :roomId AND mm.isDeleted = false
              AND (mm.createdAt < :cursorCreatedAt
                   OR (mm.createdAt = :cursorCreatedAt AND mm.mediaId < :cursorId))
            ORDER BY mm.createdAt DESC, mm.mediaId DESC
            """)
    List<MessageMedia> findGalleryAfterCursor(
            @Param("roomId") String roomId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") String cursorId,
            Pageable pageable
    );
}
