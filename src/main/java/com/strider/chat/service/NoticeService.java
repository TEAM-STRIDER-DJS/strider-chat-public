package com.strider.chat.service;

import com.strider.chat.model.entity.Message;
import com.strider.chat.model.entity.RoomNotice;
import com.strider.chat.model.entity.RoomNotice.EndReason;
import com.strider.chat.model.request.RegisterNoticeRequest;
import com.strider.chat.model.response.NoticeDetailResponse;
import com.strider.chat.model.response.NoticeResponse;
import com.strider.chat.repository.ChatRoomMemberRepository;
import com.strider.chat.repository.ChatRoomRepository;
import com.strider.chat.repository.MessageRepository;
import com.strider.chat.repository.RoomNoticeRepository;
import com.strider.strider_common_lib.error.StriderErrorCodes;
import com.strider.strider_common_lib.exception.StriderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 채팅방 공지 서비스
 *
 * ■ ChatService에서 분리한 이유:
 *   ChatService가 이미 1000줄을 넘어 공지 도메인은 별도 클래스로 둔다.
 *
 * ■ broadcast는 여기서 하지 않는다:
 *   SimpMessagingTemplate은 ChatRestController가 들고 있다(기존 읽음수신과 동일).
 *   서비스는 DB 상태만 다루고, 실시간 전파는 컨트롤러 책임이다.
 *
 * ■ 권한: 역할/방장 개념은 두지 않는다.
 *   등록·내리기·조회 방의 활성 멤버면 누구나 (남이 올린 공지도 내릴 수 있다)
 *   삭제              공지를 올린 사람(registeredBy)만
 *   삭제 권한은 원본 메시지 작성자와 무관하다 — 공지를 "올린" 사람 기준이다.
 *
 * ■ 세 동작은 모두 방 전체에 적용된다:
 *   등록   기존 공지를 교체하고 모든 멤버의 배너를 갱신
 *   내리기 모든 멤버의 배너에서 내림 (목록에는 남음)
 *   삭제   모든 멤버의 목록에서 제거 + 걸려 있던 공지면 배너도 내림
 *   사용자별 공지 상태(나만 숨기기)는 두지 않는다.
 *
 * ■ 텍스트 메시지만 공지로 걸 수 있다:
 *   사진/동영상 메시지는 등록 단계에서 거부한다. 공지는 방 전체가 오래 두고 보는
 *   글이라 본문이 한 줄로 읽혀야 하고, 미디어를 허용하면 원본을 지운 뒤에도 사진이
 *   남아야 해서 S3 삭제 정책까지 공지에 얽매인다.
 *
 * ■ 표시 문구는 등록 시점 스냅샷이다:
 *   공지 원문을 RoomNotice.content에 복사해 두므로, 원본 메시지를 삭제하거나
 *   수정해도 공지는 그대로 남는다. 모든 읽기 경로가 message 테이블을 전혀 조회하지
 *   않는다 — 조회 비용이 줄고, 삭제와 무관하게 동작이 일정해진다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NoticeService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final RoomNoticeRepository roomNoticeRepository;

    /** 공지 등록 알림 말풍선 발행 — 메시지 저장/Outbox 경로는 ChatService가 소유한다 */
    private final ChatService chatService;

    // ───────────────────────────────────────────────────────────────
    // 공지 등록 / 대체
    // ───────────────────────────────────────────────────────────────

    /**
     * 메시지를 채팅방 공지로 등록 (기존 공지가 있으면 대체)
     *
     * ■ "방당 활성 공지 1개" 보장:
     *   DB 부분 유니크 인덱스는 ddl-auto가 만들지 못하므로 이 트랜잭션 안에서
     *   기존 활성 행을 전부 REPLACED로 내린 뒤 신규 행을 저장해 보장한다.
     *
     * ■ 같은 메시지 재등록은 no-op:
     *   이미 활성인 메시지를 다시 등록하면 새 행을 만들지 않고 기존 것을 반환한다.
     *   (더블클릭이 공지 목록을 같은 메시지로 오염시키는 것 방지)
     *
     * ■ 텍스트 메시지만 등록할 수 있다:
     *   사진/동영상(IMAGE/VIDEO)과 공지 알림 말풍선(NOTICE)은 BAD_REQUEST로 거부한다.
     *   미디어를 허용하면 원본을 지운 뒤에도 사진이 남아야 해서, 공지가 미디어의
     *   S3 수명까지 붙잡게 된다. 공지는 텍스트 한 덩어리로만 다룬다.
     *
     * ■ 문구 스냅샷을 뜨는 유일한 지점:
     *   여기서만 message를 읽어 본문을 공지 행에 복사한다.
     *   이후 원본 메시지가 삭제·수정돼도 공지 문구는 영향을 받지 않는다.
     *
     * ■ 채팅 스트림에 알림 말풍선을 남긴다:
     *   등록에 성공하면 NOTICE 타입 시스템 메시지를 같은 트랜잭션에서 저장한다.
     *   말풍선의 content는 공지로 걸린 원본 메시지 내용이다 — "공지가 등록되었습니다."
     *   머리말은 클라이언트가 messageType == NOTICE 를 보고 UI에서 붙인다.
     *   재등록 no-op인 경우에는 남기지 않는다(같은 공지로 스트림이 도배되는 것 방지).
     *
     * @param roomId  채팅방 ID
     * @param userId  등록 요청 사용자 (멤버 여부 확인)
     * @param request 공지로 등록할 messageId
     * @return 등록된 공지
     */
    @Transactional
    public NoticeResponse registerNotice(String roomId, String userId, RegisterNoticeRequest request) {
        // 멤버가 아니면 공지 등록 불가
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            log.warn("[공지] 멤버 아님 | roomId: {}, userId: {}", roomId, userId);
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        if (request == null || request.getMessageId() == null || request.getMessageId().isBlank()) {
            log.warn("[공지] 등록 검증 실패 — messageId 누락 | roomId: {}, userId: {}", roomId, userId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST, "공지로 등록할 messageId가 필요합니다.");
        }
        String messageId = request.getMessageId();

        chatRoomRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));

        // 삭제된 메시지는 조회되지 않으므로 NOT_FOUND로 차단된다
        Message message = messageRepository.findByMessageIdAndIsDeletedFalse(messageId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));

        if (!message.getRoomId().equals(roomId)) {
            log.warn("[공지] 타 방 메시지 등록 시도 | roomId: {}, messageRoomId: {}, userId: {}",
                    roomId, message.getRoomId(), userId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST, "다른 채팅방의 메시지는 공지로 등록할 수 없습니다.");
        }

        // 공지 등록 알림 말풍선을 다시 공지로 거는 것은 무의미하다 (공지가 공지를 가리키게 됨)
        if (message.getMessageType() == Message.MessageType.NOTICE) {
            log.warn("[공지] 공지 알림 메시지 등록 시도 | roomId: {}, messageId: {}, userId: {}",
                    roomId, messageId, userId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST, "공지 알림 메시지는 공지로 등록할 수 없습니다.");
        }

        // 공지는 텍스트만 걸 수 있다 (사진/동영상 불가)
        if (message.getMessageType() != Message.MessageType.TEXT) {
            log.warn("[공지] 텍스트 아닌 메시지 등록 시도 | roomId: {}, messageId: {}, 유형: {}, userId: {}",
                    roomId, messageId, message.getMessageType(), userId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST, "텍스트 메시지만 공지로 등록할 수 있습니다.");
        }

        List<RoomNotice> actives = roomNoticeRepository.findByRoomIdAndEndReasonIsNullAndIsDeletedFalse(roomId);

        // 이미 같은 메시지가 공지 중이면 새 행을 만들지 않는다
        RoomNotice alreadyActive = actives.stream()
                .filter(notice -> notice.getMessageId().equals(messageId))
                .findFirst()
                .orElse(null);
        if (alreadyActive != null) {
            log.info("[공지] 이미 등록된 메시지 — 재등록 생략 | roomId: {}, messageId: {}", roomId, messageId);
            return NoticeResponse.from(alreadyActive);
        }

        // 기존 활성 공지는 "교체됨"으로 내린다 (더티체킹, 명시적 save 불필요)
        actives.forEach(notice -> endNotice(notice, EndReason.REPLACED));

        // 원문을 그대로 복사해 둔다 — 이후 원본이 삭제·수정돼도 공지는 이 값으로 보인다
        RoomNotice saved = roomNoticeRepository.save(RoomNotice.builder()
                .roomId(roomId)
                .messageId(messageId)
                .registeredBy(userId)
                .content(message.getContent())
                .build());

        // 채팅 스트림에 "공지가 등록되었습니다." 말풍선을 남긴다 (같은 트랜잭션)
        // 말풍선은 공지를 가리키고(글 확인하기), 공지는 말풍선을 가리킨다(읽은 사람 수 기준)
        Message noticeMessage = chatService.sendNoticeRegisteredMessage(
                roomId, userId, saved.getContent(), saved.getNoticeId());
        saved.setNoticeMessageId(noticeMessage.getMessageId());

        log.info("[공지] 등록 완료 | roomId: {}, noticeId: {}, messageId: {}, 말풍선: {}, 대체된 공지: {}건",
                roomId, saved.getNoticeId(), messageId,
                noticeMessage.getMessageId(), actives.size());

        return NoticeResponse.from(saved);
    }

    // ───────────────────────────────────────────────────────────────
    // 공지 내리기 (해제)
    // ───────────────────────────────────────────────────────────────

    /**
     * 현재 걸려 있는 공지를 배너에서 내린다 (목록에는 남는다)
     *
     * ■ 삭제가 아니다:
     *   공지 행은 그대로 두고 endReason만 UNREGISTERED로 표시한다.
     *   따라서 내린 공지도 공지 목록(GET /notices)에서 계속 볼 수 있다.
     *   목록에서까지 없애려면 deleteNotice(...)를 써야 한다.
     *
     * ■ 방 전체에 적용된다:
     *   사용자별 상태가 아니라 공지 행 자체의 상태라, 한 명이 내리면 모든 멤버의
     *   배너가 내려간다. 컨트롤러가 방 채널로 broadcast해 즉시 반영한다.
     *
     * ■ 멱등 no-op:
     *   내릴 공지가 없어도 예외를 던지지 않는다. 두 명이 동시에 내리거나
     *   더블클릭한 경우 뒤늦은 요청만 실패하는 일이 없도록 하기 위함이다.
     *   대신 실제로 내린 게 있는지를 반환해, 컨트롤러가 불필요한 broadcast를 건너뛴다.
     *
     * @param roomId 채팅방 ID
     * @param userId 요청 사용자 (멤버 여부 확인)
     * @return true → 실제로 내린 공지가 있었음, false → 이미 걸린 공지가 없었음
     */
    @Transactional
    public boolean unregisterNotice(String roomId, String userId) {
        // 멤버가 아니면 공지를 내릴 수 없음
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            log.warn("[공지] 멤버 아님 | roomId: {}, userId: {}", roomId, userId);
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        List<RoomNotice> actives = roomNoticeRepository.findByRoomIdAndEndReasonIsNullAndIsDeletedFalse(roomId);
        if (actives.isEmpty()) {
            log.info("[공지] 내릴 공지 없음 — 무시 | roomId: {}, userId: {}", roomId, userId);
            return false;
        }

        actives.forEach(notice -> endNotice(notice, EndReason.UNREGISTERED));
        log.info("[공지] 내리기 완료 | roomId: {}, userId: {}, 내려간 공지: {}건", roomId, userId, actives.size());

        return true;
    }

    // ───────────────────────────────────────────────────────────────
    // 공지 삭제
    // ───────────────────────────────────────────────────────────────

    /**
     * 공지를 삭제한다 — 목록에서도 사라지고, 걸려 있던 공지면 배너에서도 내려간다
     *
     * ■ 내리기와의 차이:
     *   내리기는 배너만 내리고 목록에 남기지만, 삭제는 목록에서도 제외한다.
     *   그래서 이미 내려간 과거 공지도 삭제 대상이 될 수 있어 noticeId를 받는다.
     *
     * ■ 활성 공지를 삭제하면 함께 내려간다:
     *   "삭제됐는데 배너에는 남아 있는" 상태가 없도록 endReason=DELETED를 같이 세팅한다.
     *   컨트롤러는 반환값이 true면 방 전체에 삭제를 broadcast한다.
     *
     * ■ 공지를 올린 사람(registeredBy)만 삭제할 수 있다:
     *   등록·내리기가 멤버 누구나인 것과 달리 삭제만 등록자로 제한한다.
     *   원본 메시지 작성자(Message.senderId)와는 무관하다 — 남의 메시지를 내가 공지로
     *   걸었다면 삭제 권한은 나에게 있고, 반대로 내 메시지가 남에 의해 공지로 걸렸다면
     *   나는 그 공지를 삭제할 수 없다.
     *
     * ■ 멱등 no-op:
     *   이미 삭제됐거나 없는 noticeId면 예외 없이 false를 반환한다(더블클릭 대비).
     *   타 방의 noticeId도 같은 경로로 처리되어 존재 여부가 노출되지 않는다.
     *
     * ■ DB 행은 남는다:
     *   isDeleted 소프트삭제라 "누가 무엇을 공지했었는지" 감사 이력은 보존된다.
     *
     * @param roomId   채팅방 ID
     * @param userId   요청 사용자 (멤버 여부 + 등록자 본인 여부 확인)
     * @param noticeId 삭제할 공지 ID
     * @return true → 실제로 삭제됨, false → 이미 삭제됐거나 없는 공지
     */
    @Transactional
    public boolean deleteNotice(String roomId, String userId, String noticeId) {
        // 멤버가 아니면 공지 삭제 불가
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            log.warn("[공지] 멤버 아님 | roomId: {}, userId: {}", roomId, userId);
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        RoomNotice notice = roomNoticeRepository
                .findByNoticeIdAndRoomIdAndIsDeletedFalse(noticeId, roomId)
                .orElse(null);
        if (notice == null) {
            log.info("[공지] 삭제할 공지 없음 — 무시 | roomId: {}, noticeId: {}, userId: {}",
                    roomId, noticeId, userId);
            return false;
        }

        // 공지를 올린 사람만 삭제할 수 있다 (원본 메시지 작성자와는 무관)
        if (!notice.getRegisteredBy().equals(userId)) {
            log.warn("[공지] 등록자 아님 — 삭제 거부 | roomId: {}, noticeId: {}, 등록자: {}, 요청자: {}",
                    roomId, noticeId, notice.getRegisteredBy(), userId);
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        // 걸려 있던 공지라면 배너에서도 내린다 (이미 내려간 공지는 원래 사유를 보존)
        if (notice.getEndReason() == null) {
            endNotice(notice, EndReason.DELETED);
        }

        notice.setDeleted(true);
        notice.setDeletedAt(LocalDateTime.now());

        log.info("[공지] 삭제 완료 | roomId: {}, noticeId: {}, userId: {}, 사유: {}",
                roomId, noticeId, userId, notice.getEndReason());

        return true;
    }

    // ───────────────────────────────────────────────────────────────
    // 활성 공지 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 현재 걸려 있는 공지 1건 조회 (채팅방 상단 배너용)
     *
     * ■ 방 입장 시마다 호출되는 경로이므로 공지 행 1건만 읽는다.
     *   표시 문구가 스냅샷이라 메시지 조회가 아예 필요 없다.
     *
     * ■ 원본 메시지가 삭제돼도 공지는 그대로 유지된다:
     *   공지는 "이 메시지"가 아니라 "이 내용을 공지로 걸었다"는 사실이므로,
     *   원본을 지웠다고 배너가 사라지면 안 된다. 공지를 내리려면 명시적으로
     *   내리거나(DELETE /notices/active) 삭제(DELETE /notices/{noticeId})해야 한다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회 요청 사용자 (멤버 여부 확인)
     * @return 활성 공지, 없으면 null
     */
    public NoticeResponse getActiveNotice(String roomId, String userId) {
        // 멤버가 아니면 공지 조회 불가
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        return roomNoticeRepository
                .findTopByRoomIdAndEndReasonIsNullAndIsDeletedFalseOrderByCreatedAtDesc(roomId)
                .map(NoticeResponse::from)
                .orElse(null);
    }

    // ───────────────────────────────────────────────────────────────
    // 공지 상세 조회 (말풍선의 "글 확인하기"로 진입하는 화면)
    // ───────────────────────────────────────────────────────────────

    /**
     * 공지 상세 조회
     *
     * ■ 내려간 공지도 조회된다:
     *   목록에서 과거 공지를 눌러 들어올 수 있어야 하므로 endReason은 보지 않는다.
     *   삭제된 공지만 NOT_FOUND로 막는다.
     *
     * ■ 읽은 사람 수는 조회 시점 스냅샷이다:
     *   NOTICE 말풍선을 읽은 멤버 수를 세며, 등록자는 제외한다.
     *   실시간 갱신이 아니라 화면을 열 때마다 다시 계산된다.
     *
     * ■ 원본 메시지를 조회하지 않는다:
     *   본문이 공지 행에 스냅샷으로 들어 있고 공지에 미디어가 붙는 경우가 없어,
     *   상세도 목록·배너와 똑같이 공지 테이블 단독으로 그려진다.
     *
     * @param roomId   채팅방 ID
     * @param userId   조회 요청 사용자 (멤버 여부 확인)
     * @param noticeId 조회할 공지 ID
     * @return 공지 상세 + 읽은 사람 수
     */
    public NoticeDetailResponse getNoticeDetail(String roomId, String userId, String noticeId) {
        // 멤버가 아니면 공지 조회 불가
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        RoomNotice notice = roomNoticeRepository
                .findByNoticeIdAndRoomIdAndIsDeletedFalse(noticeId, roomId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));

        return NoticeDetailResponse.from(notice, countReaders(notice));
    }

    /**
     * 공지를 읽은 사람 수 계산 (등록자 제외)
     *
     * ■ 기준 시각은 말풍선 메시지의 createdAt이다:
     *   ChatRoomMember.lastReadAt이 말풍선보다 뒤면 읽은 것으로 본다.
     *   ChatService.calculateUnreadCount의 "안 읽음" 판정과 정확히 반대 조건이라
     *   채팅방 안 읽음 배지와 숫자가 어긋나지 않는다.
     *
     * ■ 말풍선을 찾을 수 없으면 0을 반환한다:
     *   noticeMessageId 컬럼 추가 이전에 등록된 공지이거나 말풍선이 삭제된 경우다.
     *   셀 기준이 없으므로 추정하지 않고 0으로 둔다.
     *
     * @param notice 대상 공지
     * @return 읽은 멤버 수
     */
    private int countReaders(RoomNotice notice) {
        if (notice.getNoticeMessageId() == null) {
            log.info("[공지] 말풍선 연결 없음 — 읽음 수 0 처리 | noticeId: {}", notice.getNoticeId());
            return 0;
        }

        Message noticeMessage = messageRepository.findById(notice.getNoticeMessageId()).orElse(null);
        if (noticeMessage == null) {
            log.info("[공지] 말풍선 메시지 없음 — 읽음 수 0 처리 | noticeId: {}, messageId: {}",
                    notice.getNoticeId(), notice.getNoticeMessageId());
            return 0;
        }

        LocalDateTime sentAt = noticeMessage.getCreatedAt();

        return (int) chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(notice.getRoomId()).stream()
                .filter(member -> !member.getUserId().equals(notice.getRegisteredBy()))
                .filter(member -> member.getLastReadAt() != null && !member.getLastReadAt().isBefore(sentAt))
                .count();
    }

    // ───────────────────────────────────────────────────────────────
    // 공지 목록 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 공지 목록 조회 (공지 목록 화면용)
     *
     * ■ 담기는 것: 삭제되지 않은 공지 전부
     *   현재 공지 + 교체되어 내려간 공지(REPLACED) + 사용자가 내린 공지(UNREGISTERED).
     *   "내리기"는 배너에서만 내리는 동작이라 목록에는 남는다.
     *   목록에서까지 빼려면 삭제(DELETE /notices/{noticeId})해야 한다.
     *
     * ■ 활성 공지도 이 목록에 포함된다. 프런트는 endReason == null 인 항목을
     *   "현재 공지"로 구분하면 된다.
     *
     * ■ 원본 메시지가 삭제된 항목도 문구까지 그대로 남는다.
     *   등록 시점 스냅샷을 쓰므로 message 배치 조회가 필요 없다(공지 테이블 단독 조회).
     *
     * @param roomId 채팅방 ID
     * @param userId 조회 요청 사용자 (멤버 여부 확인)
     * @return 최신순 공지 목록
     */
    public List<NoticeResponse> getNoticeHistory(String roomId, String userId) {
        // 멤버가 아니면 공지 조회 불가
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        return roomNoticeRepository.findByRoomIdAndIsDeletedFalseOrderByCreatedAtDesc(roomId).stream()
                .map(NoticeResponse::from)
                .toList();
    }

    // ───────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ───────────────────────────────────────────────────────────────

    /**
     * 공지를 배너에서 내리는 유일한 지점
     *
     * endReason / endedAt을 항상 함께 세팅해
     * "endReason == null ⟺ endedAt == null" 불변식이 깨지지 않게 한다.
     * 삭제(isDeleted)는 별개의 상태이므로 여기서 건드리지 않는다.
     *
     * @param notice 내릴 공지
     * @param reason 내려가는 사유
     */
    private void endNotice(RoomNotice notice, EndReason reason) {
        notice.setEndReason(reason);
        notice.setEndedAt(LocalDateTime.now());
    }
}
