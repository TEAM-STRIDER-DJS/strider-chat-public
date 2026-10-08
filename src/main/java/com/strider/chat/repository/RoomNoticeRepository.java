package com.strider.chat.repository;

import com.strider.chat.model.entity.RoomNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 채팅방 공지 JPA Repository
 *
 * ■ 두 가지 조회 기준이 있다:
 *   활성 공지(배너) = end_reason IS NULL AND is_deleted = false  — 방당 1개
 *   공지 목록       = is_deleted = false                          — 내려간 공지 포함
 *
 *   "방당 1개"는 DB 제약이 아니라 NoticeService의 트랜잭션이 보장한다.
 */
public interface RoomNoticeRepository extends JpaRepository<RoomNotice, String> {

    /**
     * 현재 걸려 있는 공지 1건 조회 (배너용)
     *
     * ■ findTop...OrderByCreatedAtDesc 인 이유:
     *   DB에 "방당 활성 1개" 제약이 없어 동시 등록 시 활성 행이 2개가 될 수 있다.
     *   Optional 단건 파생 쿼리를 쓰면 그 순간부터 조회가 영구 500이 되므로
     *   가장 최근 1건만 집는다. (쓰기 경로가 활성 행을 전부 내리므로 자연히 정리된다)
     *
     * ■ is_deleted 조건도 함께 거는 이유:
     *   활성 공지를 삭제하면 endReason=DELETED가 함께 붙으므로 사실상 중복이지만,
     *   불변식이 깨진 행이 생기더라도 삭제된 공지가 배너에 뜨지는 않도록 방어한다.
     *
     * @param roomId 채팅방 ID
     * @return 활성 공지 (없으면 Optional.empty())
     */
    Optional<RoomNotice> findTopByRoomIdAndEndReasonIsNullAndIsDeletedFalseOrderByCreatedAtDesc(String roomId);

    /**
     * 활성 공지 전체 조회
     *
     * ■ 사용 시점:
     *   공지 등록·해제 시 기존 활성 행을 내릴 때.
     *   정상 상태면 0~1건이지만, 위 동시성 상황에서 2건 이상이면 전부 내려 자가치유한다.
     *
     * @param roomId 채팅방 ID
     * @return 활성 공지 목록
     */
    List<RoomNotice> findByRoomIdAndEndReasonIsNullAndIsDeletedFalse(String roomId);

    /**
     * 공지 목록 조회 — 삭제되지 않은 공지 전부 (내려간 공지 포함)
     *
     * 현재 공지, 새 공지로 교체된 공지, 사용자가 내린 공지가 모두 담긴다.
     * "내리기"는 배너에서만 내리는 동작이라 목록에서 빠지지 않는다.
     *
     * @param roomId 채팅방 ID
     * @return 최신순 공지 목록
     */
    List<RoomNotice> findByRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(String roomId);

    /**
     * 삭제할 공지 1건 조회
     *
     * roomId를 함께 거는 이유: 다른 방의 noticeId를 넘겨 남의 방 공지를 지우는 것을 막는다.
     * (없는 ID와 타 방 ID가 같은 결과가 되어 존재 여부도 노출하지 않는다)
     *
     * @param noticeId 공지 ID
     * @param roomId   공지가 속해야 할 채팅방 ID
     * @return 아직 삭제되지 않은 공지 (없으면 Optional.empty())
     */
    Optional<RoomNotice> findByNoticeIdAndRoomIdAndIsDeletedFalse(String noticeId, String roomId);
}
