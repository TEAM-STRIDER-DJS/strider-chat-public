package com.strider.chat.controller;

import com.strider.chat.model.request.CreateGroupRoomRequest;
import com.strider.chat.model.request.CursorQuery;
import com.strider.chat.model.request.InviteMembersRequest;
import com.strider.chat.model.request.MessageSendRequest;
import com.strider.chat.model.request.ReadReceiptRequest;
import com.strider.chat.model.request.RegisterNoticeRequest;
import com.strider.chat.model.response.ChatRoomListResponse;
import com.strider.chat.model.response.ChatRoomMemberResponse;
import com.strider.chat.model.response.ChatRoomResponse;

import com.strider.chat.model.response.MediaResponse;
import com.strider.chat.model.response.MessageResponse;
import com.strider.chat.model.response.NoticeDetailResponse;
import com.strider.chat.model.response.NoticeResponse;
import com.strider.chat.model.response.ReadReceiptResponse;
import com.strider.chat.model.response.InvitableUserResponse;
import com.strider.chat.model.response.UnreadMessagesResponse;
import com.strider.chat.service.ChatService;
import com.strider.chat.service.NoticeService;
import com.strider.chat.service.UserPresenceService;
import com.strider.strider_common_lib.error.StriderErrorCodes;
import com.strider.strider_common_lib.exception.StriderException;
import com.strider.strider_common_lib.response.StriderResponse;
import com.strider.strider_common_lib.utils.TokenUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 채팅 컨트롤러
 *
 * ■ 두 가지 역할:
 * 1. REST API — 방 생성, 메시지 이력 조회
 * 2. STOMP 핸들러 — 실시간 메시지 수신 처리
 *
 * ■ 반환 타입 (가이드 규칙):
 * REST → ResponseEntity<StriderResponse<T>>
 * STOMP → 반환값 없음 (SimpMessagingTemplate으로 내부에서 직접 push)
 *
 * ■ 에러 처리 (가이드 규칙):
 * StriderException을 던지면 GlobalStriderExceptionHandler가 자동 처리
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatRestController {
    private final ChatService chatService;
    private final NoticeService noticeService;
    private final UserPresenceService userPresenceService;
    private final TokenUtils tokenUtils;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * DIRECT 채팅방 생성 또는 조회
     *
     * ■ Notion 채팅 프로세스 기준:
     * "POST /chat/rooms/direct/{상대userId} → roomId 반환
     * DB에서 direct_key UNIQUE로 한 방만 생기게 보장
     * (이미 있으면 그 방 반환)"
     *
     * ■ 클라이언트 사용 흐름:
     * Step 1: 이 API 호출 → roomId 수신
     * Step 2: STOMP 구독 → /sub/chat/room/{roomId}
     * Step 3: STOMP 전송 → /pub/chat/send (roomId + senderId + content)
     *
     * @param senderId   방을 만들려는 사용자 userId (쿼리 파라미터)
     * @param receiverId 상대방 userId (경로 변수)
     * @return 채팅방 정보 (roomId 포함)
     */
    @PostMapping("/rooms/direct/{receiverId}")
    public ResponseEntity<StriderResponse<ChatRoomResponse>> getOrCreateDirectRoom(
            @RequestHeader HttpHeaders headers,
            @PathVariable String receiverId) {
        String senderId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 채팅방 생성/조회 | {} → {}", senderId, receiverId);

        ChatRoomResponse response = chatService.getOrCreateDirectRoom(senderId, receiverId);

        // 가이드 규칙: StriderResponse.responseBuilder(data, Class) 형태로 반환
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(response, ChatRoomResponse.class));
    }

    /**
     * 사용자의 채팅방 목록 조회
     *
     * ■ 사용 시점:
     * 채팅 탭 진입 시 참여 중인 채팅방 목록 표시
     * 
     */
    @GetMapping("/rooms")
    public ResponseEntity<StriderResponse<?>> getChatRooms(
            @RequestHeader HttpHeaders headers) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 채팅방 목록 조회 | userId: {}", userId);

        List<ChatRoomListResponse> rooms = chatService.getChatRooms(userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(rooms, List.class));
    }

    // ───────────────────────────────────────────────────────────────
    // REST API — 그룹 채팅방
    // ───────────────────────────────────────────────────────────────

    /**
     * 그룹 채팅방 생성
     *
     * @param headers Authorization 헤더 (JWT)
     * @param request roomName + memberIds
     * @return 생성된 채팅방 정보
     */
    @PostMapping("/rooms/group")
    public ResponseEntity<StriderResponse<ChatRoomResponse>> createGroupRoom(
            @RequestHeader HttpHeaders headers,
            @RequestBody CreateGroupRoomRequest request) {
        String creatorId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 그룹방 생성 | creator: {}, roomName: {}", creatorId, request.getRoomName());

        ChatRoomResponse response = chatService.createGroupRoom(creatorId, request);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(response, ChatRoomResponse.class));
    }

    /**
     * 초대 가능 사용자 목록 조회
     * 나를 팔로우하는 사용자 중 이미 채팅방 멤버가 아닌 사용자 목록
     *
     * @param headers Authorization 헤더 (JWT)
     * @param roomId  채팅방 ID
     * @return 초대 가능한 팔로워 목록
     */
    @GetMapping("/rooms/{roomId}/members/invitable")
    public ResponseEntity<StriderResponse<?>> getInvitableUsers(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 초대 가능 사용자 조회 | roomId: {}, userId: {}", roomId, userId);

        List<InvitableUserResponse> invitableUsers = chatService.getInvitableUsers(roomId, userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(invitableUsers, List.class));
    }

    /**
     * 그룹 채팅방 멤버 초대
     *
     * @param headers Authorization 헤더 (JWT)
     * @param roomId  채팅방 ID
     * @param request 초대할 사용자 ID 목록
     * @return 새로 초대된 멤버 목록
     */
    @PostMapping("/rooms/{roomId}/members")
    public ResponseEntity<StriderResponse<?>> inviteMembers(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @RequestBody InviteMembersRequest request) {
        String inviterId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 멤버 초대 | roomId: {}, inviter: {}", roomId, inviterId);

        List<ChatRoomMemberResponse> invitedMembers = chatService.inviteMembers(roomId, inviterId, request);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(invitedMembers, List.class));
    }

    /**
     * 그룹 채팅방 퇴장
     *
     * @param headers Authorization 헤더 (JWT)
     * @param roomId  채팅방 ID
     * @return 성공 여부
     */
    @DeleteMapping("/rooms/{roomId}/members/me")
    public ResponseEntity<StriderResponse<?>> leaveGroupRoom(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 그룹방 퇴장 | roomId: {}, userId: {}", roomId, userId);

        chatService.leaveGroupRoom(roomId, userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(true, Boolean.class));
    }

    /**
     * 채팅방 멤버 목록 조회
     *
     * @param headers Authorization 헤더 (JWT)
     * @param roomId  채팅방 ID
     * @return 활성 멤버 목록
     */
    @GetMapping("/rooms/{roomId}/members")
    public ResponseEntity<StriderResponse<?>> getRoomMembers(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 멤버 목록 조회 | roomId: {}, userId: {}", roomId, userId);

        List<ChatRoomMemberResponse> members = chatService.getRoomMembers(roomId, userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(members, List.class));
    }

    // ───────────────────────────────────────────────────────────────
    // REST API — 메시지 이력 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 메시지 단건 조회
     *
     * @param messageId 조회할 메시지 UUID
     * @return 메시지 상세 정보
     */
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<StriderResponse<MessageResponse>> getMessage(
            @PathVariable String messageId) {
        log.info("[REST] 메시지 단건 조회 | messageId: {}", messageId);

        MessageResponse response = chatService.getMessage(messageId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(response, MessageResponse.class));
    }

    /**
     * 채팅방 안에서 메시지 조회 (커서 기반 페이지네이션)
     *
     * @param roomId          채팅방 ID (경로 변수)
     * @param userId          조회 요청 사용자 (멤버 확인용)
     * @param size            가져올 수 (기본 30)
     * @param cursorCreatedAt 이전 페이지 마지막 createdAt
     * @param cursorId        이전 페이지 마지막 messageId
     * @return 메시지 목록
     */
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<StriderResponse<?>> getMessages(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @ModelAttribute CursorQuery query) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 메시지 이력 조회 | roomId: {}, userId: {}", roomId, userId);

        int size = query.size() == null ? 30 : query.size();

        List<MessageResponse> messages = chatService.getMessages(roomId, userId, size, query.cursorCreatedAt(),
                query.cursorId(), query.direction());

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(messages, List.class));
    }

    /**
     * 채팅방 입장 시 안 읽은 메시지 기준 양방향 조회
     *
     * ■ 사용 시점:
     * 채팅방 입장 시 lastReadAt 기준으로 이전 메시지(컨텍스트) + 안 읽은 메시지 로드
     *
     * ■ 응답:
     * - messages: 시간순 정렬된 메시지 목록 (이전 + 안 읽은)
     * - unreadCount: 안 읽은 메시지 수
     * - lastReadMessageId: 마지막 읽은 메시지 ID (구분선 렌더링용)
     *
     * @param roomId 채팅방 ID
     * @param size   각 방향별 가져올 메시지 수 (기본 30)
     * @return 안 읽은 메시지 기준 양방향 메시지
     */
    @GetMapping("/rooms/{roomId}/messages/unread")
    public ResponseEntity<StriderResponse<UnreadMessagesResponse>> getMessagesAroundUnread(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @RequestParam(defaultValue = "30") int size) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 안 읽은 메시지 기준 조회 | roomId: {}, userId: {}", roomId, userId);

        UnreadMessagesResponse response = chatService.getMessagesAroundUnread(roomId, userId, size);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(response, UnreadMessagesResponse.class));
    }

    // ───────────────────────────────────────────────────────────────
    // REST API — 미디어 갤러리
    // ───────────────────────────────────────────────────────────────
    /**
     * 채팅방 사진/동영상 갤러리 조회 (커서 기반 페이지네이션)
     *
     * ■ 응답:
     * 사진·동영상을 섞어 최신순(createdAt DESC, mediaId DESC)으로 반환
     *
     * ■ 다음 페이지:
     * 응답 마지막 항목의 createdAt·mediaId를 cursorCreatedAt·cursorId로 넘긴다.
     * 커서보다 과거인 항목만 오므로 클라이언트는 기존 목록 뒤에 append 하면 된다.
     * (CursorQuery의 direction은 갤러리에서 사용하지 않는다 — 항상 최신순 단방향)
     *
     * @param roomId 채팅방 ID (경로 변수)
     * @param query  커서 조회 조건 (size 기본 30, cursorCreatedAt, cursorId)
     * @return 미디어 목록
     */
    @GetMapping("/rooms/{roomId}/media")
    public ResponseEntity<StriderResponse<?>> getRoomMedia(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @ModelAttribute CursorQuery query) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 방 미디어 갤러리 조회 | roomId: {}, userId: {}", roomId, userId);

        int size = query.size() == null ? 30 : query.size();

        List<MediaResponse> media = chatService.getRoomMedia(roomId, userId, size,
                query.cursorCreatedAt(), query.cursorId());

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(media, List.class));
    }

    // ───────────────────────────────────────────────────────────────
    // REST API — 공지(Notice)
    // ───────────────────────────────────────────────────────────────
    /**
     * 메시지를 채팅방 공지로 등록 (기존 공지가 있으면 대체)
     *
     * ■ 권한:
     * 역할/방장 개념 없이 방의 활성 멤버면 누구나 등록할 수 있다.
     *
     * ■ 실시간 전파:
     * Kafka/Outbox를 타지 않고 여기서 바로 broadcast 한다.
     * 공지는 유실돼도 진실 원천이 DB 행이라 GET /notices/active 한 번으로 복구되는 반면,
     * Outbox 경로를 타면 5초 지연이 붙기 때문이다. (읽음수신과 동일한 판단)
     *
     * @param roomId  채팅방 ID (경로 변수)
     * @param request 공지로 등록할 messageId
     * @return 등록된 공지
     */
    @PostMapping("/rooms/{roomId}/notices")
    public ResponseEntity<StriderResponse<?>> registerNotice(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @RequestBody RegisterNoticeRequest request) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 공지 등록 | roomId: {}, userId: {}", roomId, userId);

        NoticeResponse notice = noticeService.registerNotice(roomId, userId, request);

        // 공지 변경 STOMP broadcast → 방에 있는 멤버의 공지 배너 즉시 갱신
        messagingTemplate.convertAndSend(
                "/sub/chat/room/" + roomId + "/notice",
                notice);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(notice, NoticeResponse.class));
    }

    /**
     * 현재 걸려 있는 공지 내리기 (해제)
     *
     * ■ 삭제가 아니다:
     * 배너에서만 내려가고 공지 목록(GET /notices)에는 그대로 남는다.
     * 목록에서까지 없애려면 DELETE /notices/{noticeId} 를 쓴다.
     *
     * ■ 방 전체에 적용된다:
     * 사용자별 상태가 아니라 공지 자체의 상태라, 한 명이 내리면 모든 멤버의 배너가 내려간다.
     *
     * ■ 경로가 /notices/active 인 이유:
     * noticeId를 몰라도 "지금 걸린 것"을 내릴 수 있어야 한다.
     * 프런트가 활성 공지를 먼저 조회해 ID를 알아낼 필요가 없다.
     *
     * ■ 멱등:
     * 내릴 공지가 없어도 200 true. 다만 실제로 내려간 게 있을 때만 broadcast 한다.
     *
     * @param roomId 채팅방 ID (경로 변수)
     * @return 항상 true
     */
    @DeleteMapping("/rooms/{roomId}/notices/active")
    public ResponseEntity<StriderResponse<?>> unregisterNotice(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 공지 내리기 | roomId: {}, userId: {}", roomId, userId);

        boolean cleared = noticeService.unregisterNotice(roomId, userId);

        // noticeId=null 인 payload → 구독자는 배너만 내리고 목록은 건드리지 않는다
        if (cleared) {
            messagingTemplate.convertAndSend(
                    "/sub/chat/room/" + roomId + "/notice",
                    NoticeResponse.cleared(roomId));
        }

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(true, Boolean.class));
    }

    /**
     * 공지 삭제 — 목록에서도 제거하고, 걸려 있던 공지면 배너에서도 내린다
     *
     * ■ 내리기와의 차이:
     * 내리기는 배너만 내리고 목록에 남기지만, 삭제는 목록에서도 사라진다.
     * 이미 내려간 과거 공지도 삭제할 수 있어야 하므로 noticeId를 경로로 받는다.
     *
     * ■ 방 전체에 적용된다:
     * 삭제한 사람뿐 아니라 모든 멤버의 목록·배너에서 사라진다.
     *
     * ■ 권한 — 공지를 올린 사람만:
     * 등록·내리기가 멤버 누구나인 것과 달리 삭제는 registeredBy 본인만 가능하다(아니면 FORBIDDEN).
     * 원본 메시지 작성자와는 무관하다 — 남의 메시지를 내가 공지로 걸었다면 삭제 권한은 나에게 있다.
     *
     * ■ 멱등:
     * 이미 삭제됐거나 없는 noticeId여도 200 true. 실제로 삭제된 경우에만 broadcast 한다.
     * (존재하지 않는 공지는 권한 검사 전에 걸러지므로 FORBIDDEN이 아니라 200 true)
     *
     * @param roomId   채팅방 ID (경로 변수)
     * @param noticeId 삭제할 공지 ID (경로 변수)
     * @return 항상 true
     */
    @DeleteMapping("/rooms/{roomId}/notices/{noticeId}")
    public ResponseEntity<StriderResponse<?>> deleteNotice(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @PathVariable String noticeId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 공지 삭제 | roomId: {}, noticeId: {}, userId: {}", roomId, noticeId, userId);

        boolean removed = noticeService.deleteNotice(roomId, userId, noticeId);

        // noticeId를 실어 보낸다 → 구독자는 목록에서 제거하고, 배너의 공지면 배너도 내린다
        if (removed) {
            messagingTemplate.convertAndSend(
                    "/sub/chat/room/" + roomId + "/notice",
                    NoticeResponse.removed(roomId, noticeId));
        }

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(true, Boolean.class));
    }

    /**
     * 현재 걸려 있는 공지 조회 (채팅방 상단 배너용)
     *
     * ■ 사용 시점:
     * 채팅방 입장 시. broadcast는 접속해 있는 동안만 오므로 입장 시점의 초기 상태를 이걸로 채운다.
     *
     * ■ data가 null인 것이 정상 응답이다:
     * 공지가 없는 방, 공지를 내린 방, 걸려 있던 공지를 삭제한 방 모두 null.
     * 클라이언트는 null이면 배너를 숨기면 된다.
     *
     * ■ 원본 메시지를 삭제해도 공지는 사라지지 않는다:
     * 표시 문구가 등록 시점 스냅샷이라 그대로 노출된다. 다만 messageId가 가리키는
     * 메시지는 없을 수 있으므로, "원본으로 이동"은 실패할 수 있다고 보고 처리해야 한다.
     *
     * @param roomId 채팅방 ID (경로 변수)
     * @return 활성 공지, 없으면 null
     */
    @GetMapping("/rooms/{roomId}/notices/active")
    public ResponseEntity<StriderResponse<?>> getActiveNotice(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 활성 공지 조회 | roomId: {}, userId: {}", roomId, userId);

        NoticeResponse notice = noticeService.getActiveNotice(roomId, userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(notice, NoticeResponse.class));
    }

    /**
     * 공지 상세 조회 (말풍선의 "글 확인하기" 진입 화면)
     *
     * ■ 읽은 사람 수 포함:
     * NOTICE 말풍선을 읽은 멤버 수를 등록자 제외하고 센다.
     * 조회 시점 스냅샷이라 실시간 갱신되지 않는다 — 화면을 다시 열면 다시 계산된다.
     *
     * ■ 내려간 공지도 조회된다:
     * 목록에서 과거 공지를 눌러 들어올 수 있어야 한다. 삭제된 공지만 NOT_FOUND.
     *
     * @param roomId   채팅방 ID (경로 변수)
     * @param noticeId 조회할 공지 ID (경로 변수)
     * @return 공지 상세 + 읽은 사람 수
     */
    @GetMapping("/rooms/{roomId}/notices/{noticeId}")
    public ResponseEntity<StriderResponse<?>> getNoticeDetail(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId,
            @PathVariable String noticeId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 공지 상세 조회 | roomId: {}, noticeId: {}, userId: {}", roomId, noticeId, userId);

        NoticeDetailResponse detail = noticeService.getNoticeDetail(roomId, userId, noticeId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(detail, NoticeDetailResponse.class));
    }

    /**
     * 공지 목록 조회 (공지 목록 화면용)
     *
     * ■ /notices/active 와의 차이:
     * active는 "지금 뭐가 걸려 있나"(0~1건, 배너용), 이쪽은 "무슨 공지들이 있었나"(N건, 목록용).
     * 현재 공지도 이 목록에 포함되므로 두 응답에 함께 나온다.
     *
     * ■ 담기는 것:
     * 삭제되지 않은 공지 전부 — 현재 공지 + 교체되어 내려간 공지 + 사용자가 내린 공지.
     * 내리기는 배너에서만 내리는 동작이라 목록에는 남고, 삭제해야 목록에서 빠진다.
     * 프런트는 endReason == null 인 항목을 "현재 공지"로 표시하면 된다.
     *
     * @param roomId 채팅방 ID (경로 변수)
     * @return 최신순 공지 목록
     */
    @GetMapping("/rooms/{roomId}/notices")
    public ResponseEntity<StriderResponse<?>> getNoticeHistory(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 공지 목록 조회 | roomId: {}, userId: {}", roomId, userId);

        List<NoticeResponse> notices = noticeService.getNoticeHistory(roomId, userId);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(notices, List.class));
    }

    // ───────────────────────────────────────────────────────────────
    // REST API — 읽음 처리
    // ───────────────────────────────────────────────────────────────
    /**
     * 채팅방 입장 시 안 읽은 메시지 일괄 읽음 처리
     *
     * ■ 사용 시점:
     * 클라이언트가 채팅방에 입장할 때 호출
     * → 입장 시각 기준으로 그 이전 메시지만 읽음 처리
     * → 입장 이후 도착한 메시지는 unread 상태 유지
     *
     * ■ 클라이언트 흐름:
     * Step 1: POST /api/v1/chat/rooms/{roomId}/read ← 이 API (userId는 JWT에서 추출)
     * Step 2: STOMP 구독 /sub/chat/room/{roomId}
     * Step 3: GET /api/v1/chat/rooms/{roomId}/messages/unread (안 읽은 메시지 기준 양방향 조회)
     *
     * @param roomId 채팅방 ID
     * @param userId 입장한 사용자 ID
     * @return 읽음 처리된 메시지 수
     */
    @PostMapping("/rooms/{roomId}/read")
    public ResponseEntity<StriderResponse<?>> markMessagesAsRead(
            @RequestHeader HttpHeaders headers,
            @PathVariable String roomId) {
        String userId = tokenUtils.getUidFrom(headers);
        log.info("[REST] 읽음 처리 | roomId: {}, userId: {}", roomId, userId);

        LocalDateTime readAt = chatService.markMessagesAsRead(roomId, userId);

        // 읽음 이벤트 STOMP broadcast → 채팅방 참여자에게 실시간 알림
        ReadReceiptResponse response = ReadReceiptResponse.builder()
                .roomId(roomId)
                .userId(userId)
                .readAt(readAt)
                .build();

        messagingTemplate.convertAndSend(
                "/sub/chat/room/" + roomId + "/read",
                response);

        return ResponseEntity
                .status(HttpStatus.OK)
                .body(StriderResponse.responseBuilder(true, Boolean.class));
    }

    // ───────────────────────────────────────────────────────────────
    // STOMP WebSocket 핸들러
    // ───────────────────────────────────────────────────────────────

    /**
     * 채팅방 입장 STOMP 핸들러
     *
     * ■ 동작:
     *   Redis active:room:{userId} = roomId 저장
     *   → ChatMessageConsumer에서 알림 발송 여부 판단에 사용
     *
     * @param payload roomId를 담은 맵
     * @param principal JWT에서 추출한 사용자 정보
     */
    @MessageMapping("/chat/enter")
    public void enterRoom(@Payload Map<String, String> payload, Principal principal) {
        String userId = principal.getName();
        String roomId = payload.get("roomId");

        if (roomId == null || roomId.isBlank()) {
            log.warn("[STOMP] 채팅방 입장 검증 실패 — roomId 누락 | userId: {}", userId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        userPresenceService.enterRoom(userId, roomId);
        log.debug("[STOMP] 채팅방 입장 | userId: {}, roomId: {}", userId, roomId);
    }

    /**
     * 채팅방 퇴장 STOMP 핸들러
     *
     * ■ 동작:
     *   Redis active:room:{userId} 삭제
     *   → 이후 수신 메시지는 인앱 알림 또는 FCM 대상이 됨
     *
     * @param principal JWT에서 추출한 사용자 정보
     */
    @MessageMapping("/chat/leave")
    public void leaveRoom(Principal principal) {
        String userId = principal.getName();
        userPresenceService.leaveRoom(userId);
        log.debug("[STOMP] 채팅방 퇴장 | userId: {}", userId);
    }

    /**
     * 메시지 전송 STOMP 핸들러
     *
     * ■ 동작 흐름:
     * 클라이언트 → /pub/chat/send (STOMP) → 이 메서드
     * → JWT에서 senderId 추출 → 서비스에서 멤버 확인 + DB 저장
     *
     * ■ 인증:
     * STOMP CONNECT 시 JWT 검증 → Principal에 userId 설정 (StompAuthInterceptor)
     * senderId는 Principal에서 추출하므로 클라이언트가 위조 불가
     *
     * @param request   roomId + content (senderId는 서버에서 추출)
     * @param principal JWT에서 추출한 사용자 정보
     */
    @MessageMapping("/chat/send")
    public void sendMessage(@Payload MessageSendRequest request, Principal principal) {
        String senderId = principal.getName();

        if (request.getRoomId() == null || request.getRoomId().isBlank()) {
            log.warn("[STOMP] 메시지 검증 실패 — roomId 누락");
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        // content·media 구성 검증은 ChatService.sendMessage()에서 수행

        // JWT에서 추출한 senderId로 덮어쓰기 (클라이언트 값 무시)
        request.setSenderId(senderId);

        log.info("[STOMP] 메시지 수신 | roomId: {}, sender: {}", request.getRoomId(), senderId);

        chatService.sendMessage(request);
    }

    /**
     * STOMP 읽음 처리 핸들러
     *
     * ■ 동작 흐름:
     * 클라이언트 → /pub/chat/read (STOMP) → 이 메서드
     * → JWT에서 userId 추출 → lastReadAt 갱신 → broadcast
     *
     * @param request   roomId (userId는 서버에서 추출)
     * @param principal JWT에서 추출한 사용자 정보
     */
    @MessageMapping("/chat/read")
    public void markAsRead(@Payload ReadReceiptRequest request, Principal principal) {
        String userId = principal.getName();

        if (request.getRoomId() == null || request.getRoomId().isBlank()) {
            log.warn("[STOMP] 읽음 처리 검증 실패 — roomId 누락");
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        log.info("[STOMP] 읽음 처리 | roomId: {}, userId: {}", request.getRoomId(), userId);

        LocalDateTime readAt = chatService.markMessagesAsRead(request.getRoomId(), userId);

        ReadReceiptResponse response = ReadReceiptResponse.builder()
                .roomId(request.getRoomId())
                .userId(userId)
                .readAt(readAt)
                .build();

        messagingTemplate.convertAndSend(
                "/sub/chat/room/" + request.getRoomId() + "/read",
                response);
    }

    /**
     * STOMP 핸들러 예외 처리
     *
     * REST와 달리 STOMP는 GlobalStriderExceptionHandler를 타지 않는다.
     * 핸들러가 없으면 예외가 서버 로그에만 남고 클라이언트는 아무 응답도 못 받으므로,
     * 알림과 동일한 규약(/sub/user/{userId}/...)으로 발신자에게만 사유를 push한다.
     *
     * ■ 발신자에게 push되는 페이로드 예시:
     *   { "code": "BAD_REQUEST", "status": 400, "message": "이미지는 최대 10장까지 보낼 수 있습니다 (요청: 15장)" }
     */
    @MessageExceptionHandler(StriderException.class)
    public void handleStompException(StriderException e, Principal principal) {
        String userId = principal.getName();
        log.warn("[STOMP] 처리 실패 | userId: {}, code: {}, message: {}",
                userId, e.getErrorCode().getCode(), e.getMessage());

        messagingTemplate.convertAndSend(
                "/sub/user/" + userId + "/error",
                Map.of(
                        "code", e.getErrorCode().getCode(),
                        "status", e.getErrorCode().getStatus(),
                        "message", e.getMessage()
                ));
    }
}
