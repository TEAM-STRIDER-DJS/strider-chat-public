package com.strider.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strider.chat.kafka.event.ChatEventType;
import com.strider.chat.kafka.event.ChatMessageEvent;
import com.strider.chat.model.entity.ChatRoom;
import com.strider.chat.model.entity.ChatRoomMember;
import com.strider.chat.model.entity.Message;
import com.strider.chat.model.entity.Message.MessageType;
import com.strider.chat.model.entity.MessageMedia;
import com.strider.chat.model.entity.Outbox;
import com.strider.chat.model.request.CreateGroupRoomRequest;
import com.strider.chat.model.request.InviteMembersRequest;
import com.strider.chat.model.request.MessageSendRequest;
import com.strider.chat.model.request.MessageSendRequest.MediaItem;
import com.strider.chat.model.request.ScrollDirection;
import com.strider.chat.model.response.ChatRoomListResponse;
import com.strider.chat.model.response.ChatRoomMemberResponse;
import com.strider.chat.model.response.ChatRoomResponse;
import com.strider.chat.model.response.MediaResponse;
import com.strider.chat.model.response.MessageResponse;
import com.strider.chat.model.response.Envelope;
import com.strider.chat.model.response.InvitableUserResponse;
import com.strider.chat.model.response.UnreadMessagesResponse;
import com.strider.chat.model.response.UserFollowResponse;
import com.strider.chat.client.UserProfileClient;
import com.strider.chat.util.EnvelopeUtils;
import com.strider.chat.util.MessagePreviewUtils;
import com.strider.chat.repository.ChatRoomMemberRepository;
import com.strider.chat.repository.ChatRoomRepository;
import com.strider.chat.repository.MessageMediaRepository;
import com.strider.chat.repository.MessageRepository;
import com.strider.chat.repository.OutboxRepository;
import com.strider.strider_common_lib.error.StriderErrorCodes;
import com.strider.strider_common_lib.exception.StriderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 채팅 비즈니스 로직 서비스
 *
 * ■ 담당 기능:
 *   1. DIRECT 채팅방 생성 또는 조회 (getOrCreateDirectRoom)
 *   2. 메시지 전송 — DB 저장 + Outbox 저장 (sendMessage)
 *   3. 채팅방 메시지 이력 조회 — 커서 기반 페이지네이션 (getMessages)
 *
 * ■ 변경된 흐름 (Outbox 패턴 적용):
 *   이전: 메시지 저장 → SimpMessagingTemplate으로 직접 broadcast (원자성 미보장)
 *   이후: 메시지 저장 + Outbox 저장 (같은 @Transactional)
 *         → OutboxScheduler가 Kafka 발행
 *         → ChatMessageConsumer가 소비 후 STOMP broadcast (원자성 보장)
 *
 * ■ Outbox 패턴 원자성 보장:
 *   Message 저장과 Outbox 저장이 같은 트랜잭션 안에 있으므로
 *   둘 다 성공하거나 둘 다 롤백 → Kafka 발행 누락 없음
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    /** 한 메시지에 첨부 가능한 최대 이미지 장수 (비디오는 항상 1개) */
    private static final int MAX_IMAGE_COUNT = 10;

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final MessageMediaRepository messageMediaRepository;
    private final OutboxRepository outboxRepository;
    private final UserProfileClient userProfileClient;
    private final UserPresenceService userPresenceService;

    /**
     * JSON 직렬화용 ObjectMapper
     * ChatMessageEvent → JSON 문자열 변환 후 Outbox.payload에 저장
     */
    private final ObjectMapper objectMapper;

    // ───────────────────────────────────────────────────────────────
    // 채팅방 생성 / 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * DIRECT 채팅방 생성 또는 조회
     *
     * ■ Notion 프로세스 기준:
     *   "POST /chat/rooms/direct/{상대userId} → roomId 반환
     *    DB에서 direct_key UNIQUE로 한 방만 생기게 보장
     *    (이미 있으면 그 방 반환)"
     *
     * ■ 동시성 처리:
     *   두 사용자가 동시에 방 생성을 시도해도
     *   directKey UNIQUE 제약 덕분에 DB 레벨에서 하나만 저장됨
     *
     * @param senderId   방을 만들려는 사용자 userId
     * @param receiverId 상대방 userId
     * @return 기존 또는 신규 채팅방 정보 (roomId 포함)
     */
    @Transactional
    public ChatRoomResponse getOrCreateDirectRoom(String senderId, String receiverId) {
        log.debug("[채팅방] 조회/생성 시작 | {} ↔ {}", senderId, receiverId);

        // senderId는 컨트롤러에서 JWT로 인증된 값이므로 검증 생략
        // receiverId 존재 여부 확인 (Feign → strider-user-profile)
        Envelope<Boolean> response = userProfileClient.isExistUser(receiverId);
        log.info("[채팅방] 사용자 존재 확인 | receiverId: {}, response: {}", receiverId, response.data());
        Boolean isExist = EnvelopeUtils.getOrNull(response);
        if (isExist == null || !isExist) {
            log.warn("[채팅방] 상대방 사용자 없음 | receiverId: {}", receiverId);
            throw new StriderException(StriderErrorCodes.NOT_FOUND);
        }

        // directKey: 두 userId 사전순 정렬 → 호출 순서 무관하게 동일한 key 생성
        String directKey = ChatRoom.buildDirectKey(senderId, receiverId);

        // 기존 방 조회
        Optional<ChatRoom> existingRoom = chatRoomRepository.findByDirectKeyAndIsDeletedFalse(directKey);

        if (existingRoom.isPresent()) {
            log.debug("[채팅방] 기존 방 반환 | roomId: {}", existingRoom.get().getRoomId());
            return ChatRoomResponse.from(existingRoom.get(), false);
        }

        // 신규 방 생성
        ChatRoom newRoom = ChatRoom.builder()
                .roomType(ChatRoom.RoomType.DIRECT)
                .directKey(directKey)
                .build();

        ChatRoom savedRoom = chatRoomRepository.save(newRoom);
        log.debug("[채팅방] 신규 방 생성 | roomId: {}", savedRoom.getRoomId());

        // 두 사용자를 멤버로 등록 (DIRECT방: 항상 2명)
        LocalDateTime now = LocalDateTime.now();

        chatRoomMemberRepository.save(ChatRoomMember.builder()
                .roomId(savedRoom.getRoomId())
                .userId(senderId)
                .lastReadAt(now)
                .build());

        chatRoomMemberRepository.save(ChatRoomMember.builder()
                .roomId(savedRoom.getRoomId())
                .userId(receiverId)
                .lastReadAt(now)
                .build());

        log.debug("[채팅방] 멤버 등록 완료 | sender: {}, receiver: {}", senderId, receiverId);
        return ChatRoomResponse.from(savedRoom, true);
    }

    // ───────────────────────────────────────────────────────────────
    // 그룹 채팅방 생성
    // ───────────────────────────────────────────────────────────────

    /**
     * 그룹 채팅방 생성
     *
     * ■ 동작:
     *   1. memberIds 중복 제거 + 본인 ID 필터링
     *   2. 본인 포함 최소 2명 검증
     *   3. 각 멤버 존재 확인 (Feign)
     *   4. ChatRoom(GROUP) + ChatRoomMember 저장
     *
     * @param creatorId 생성자 userId
     * @param request   roomName + memberIds
     * @return 생성된 채팅방 정보
     */
    @Transactional
    public ChatRoomResponse createGroupRoom(String creatorId, CreateGroupRoomRequest request) {
        log.debug("[그룹방] 생성 시작 | creator: {}", creatorId);

        // 중복 제거 + 본인 ID 필터링
        List<String> memberIds = request.getMemberIds().stream()
                .distinct()
                .filter(id -> !id.equals(creatorId))
                .toList();

        if (memberIds.isEmpty()) {
            log.warn("[그룹방] 초대 대상 없음 | creator: {}", creatorId);
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        // 생성자의 팔로워 목록 조회 — 팔로워만 초대 가능
        List<UserFollowResponse> followers = EnvelopeUtils.getOrNull(userProfileClient.getFollowers(creatorId));
        log.info("[그룹방] 팔로워 조회 결과 | creator: {}, followers: {}", creatorId, followers);
        Set<String> followerIds = (followers == null) ? Set.of()
                : followers.stream().map(UserFollowResponse::userId).collect(Collectors.toSet());
        log.info("[그룹방] 팔로워 ID 집합 | followerIds: {}", followerIds);

        for (String memberId : memberIds) {
            if (!followerIds.contains(memberId)) {
                log.warn("[그룹방] 팔로워가 아닌 사용자 초대 시도 | creator: {}, memberId: {}", creatorId, memberId);
                throw new StriderException(StriderErrorCodes.FORBIDDEN);
            }
        }

        // 채팅방 생성
        ChatRoom newRoom = ChatRoom.builder()
                .roomType(ChatRoom.RoomType.GROUP)
                .roomName(request.getRoomName())
                .build();
        ChatRoom savedRoom = chatRoomRepository.save(newRoom);
        log.debug("[그룹방] 방 생성 완료 | roomId: {}", savedRoom.getRoomId());

        // 멤버 등록 (생성자 + 초대 멤버)
        LocalDateTime now = LocalDateTime.now();

        chatRoomMemberRepository.save(ChatRoomMember.builder()
                .roomId(savedRoom.getRoomId())
                .userId(creatorId)
                .lastReadAt(now)
                .build());

        for (String memberId : memberIds) {
            chatRoomMemberRepository.save(ChatRoomMember.builder()
                    .roomId(savedRoom.getRoomId())
                    .userId(memberId)
                    .lastReadAt(now)
                    .build());
        }

        log.info("[그룹방] 생성 완료 | roomId: {}, 멤버 수: {}", savedRoom.getRoomId(), memberIds.size() + 1);
        return ChatRoomResponse.from(savedRoom, true);
    }

    // ───────────────────────────────────────────────────────────────
    // 초대 가능 사용자 목록 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 초대 가능 사용자 목록 조회
     *
     * ■ 동작:
     *   나를 팔로우하는 전체 사용자 목록을 반환하되,
     *   이미 채팅방 멤버인 사용자는 alreadyMember=true로 표시
     *
     * @param roomId 채팅방 ID
     * @param userId 요청자 ID
     * @return 팔로워 목록 (alreadyMember 포함)
     */
    public List<InvitableUserResponse> getInvitableUsers(String roomId, String userId) {
        // 멤버 확인
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        // 방 존재 + GROUP 타입 확인
        ChatRoom room = chatRoomRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));
        if (room.getRoomType() != ChatRoom.RoomType.GROUP) {
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        // 팔로워 목록 조회
        List<UserFollowResponse> followers = EnvelopeUtils.getOrNull(userProfileClient.getFollowers(userId));
        if (followers == null || followers.isEmpty()) {
            return List.of();
        }

        // 현재 멤버 userId Set
        Set<String> existingMemberIds = chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .stream()
                .map(ChatRoomMember::getUserId)
                .collect(Collectors.toSet());

        // 전체 팔로워 반환 — 이미 멤버인 경우 alreadyMember=true
        return followers.stream()
                .map(f -> InvitableUserResponse.from(f, existingMemberIds.contains(f.userId())))
                .toList();
    }

    // ───────────────────────────────────────────────────────────────
    // 그룹 채팅방 멤버 초대
    // ───────────────────────────────────────────────────────────────

    /**
     * 그룹 채팅방 멤버 초대
     *
     * ■ 제약:
     *   - GROUP방만 가능 (DIRECT면 BAD_REQUEST)
     *   - 요청자가 멤버여야 함
     *   - 초대 대상이 요청자의 팔로워여야 함
     *   - 이미 멤버인 사용자는 skip
     *
     * @param roomId    채팅방 ID
     * @param inviterId 초대하는 사용자 ID
     * @param request   초대할 사용자 ID 목록
     * @return 새로 초대된 멤버 목록
     */
    @Transactional
    public List<ChatRoomMemberResponse> inviteMembers(String roomId, String inviterId, InviteMembersRequest request) {
        log.debug("[그룹방] 멤버 초대 시작 | roomId: {}, inviter: {}", roomId, inviterId);

        // 방 존재 + GROUP 타입 확인
        ChatRoom room = chatRoomRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));
        if (room.getRoomType() != ChatRoom.RoomType.GROUP) {
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        // 요청자 멤버 확인
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, inviterId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        // 팔로워 목록 조회 → 팔로워 userId Set
        List<UserFollowResponse> followers = EnvelopeUtils.getOrNull(userProfileClient.getFollowers(inviterId));
        Set<String> followerIds = (followers != null)
                ? followers.stream().map(UserFollowResponse::userId).collect(Collectors.toSet())
                : Set.of();

        LocalDateTime now = LocalDateTime.now();
        List<ChatRoomMemberResponse> invitedMembers = new ArrayList<>();

        for (String memberId : request.getMemberIds().stream().distinct().toList()) {
            // 팔로워인지 확인
            if (!followerIds.contains(memberId)) {
                log.warn("[그룹방] 팔로워가 아닌 사용자 초대 시도 | inviter: {}, target: {}", inviterId, memberId);
                throw new StriderException(StriderErrorCodes.FORBIDDEN);
            }

            // 이미 활성 멤버면 skip
            if (chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, memberId)) {
                continue;
            }

            // 퇴장한 멤버(soft-deleted)가 있으면 재활성화, 없으면 신규 생성
            ChatRoomMember member = chatRoomMemberRepository.findByRoomIdAndUserId(roomId, memberId)
                    .map(existing -> {
                        existing.setDeleted(false);
                        existing.setDeletedAt(null);
                        existing.setLastReadAt(now);
                        return existing;
                    })
                    .orElseGet(() -> ChatRoomMember.builder()
                            .roomId(roomId)
                            .userId(memberId)
                            .lastReadAt(now)
                            .build());

            ChatRoomMember savedMember = chatRoomMemberRepository.save(member);
            invitedMembers.add(ChatRoomMemberResponse.from(savedMember));
        }

        log.info("[그룹방] 멤버 초대 완료 | roomId: {}, 신규 멤버: {}명", roomId, invitedMembers.size());
        return invitedMembers;
    }

    // ───────────────────────────────────────────────────────────────
    // 그룹 채팅방 퇴장
    // ───────────────────────────────────────────────────────────────

    /**
     * 그룹 채팅방 퇴장
     *
     * ■ 동작:
     *   1. GROUP방 확인 (DIRECT면 BAD_REQUEST)
     *   2. 멤버 레코드 soft-delete
     *   3. 남은 멤버 0명이면 방도 soft-delete
     *
     * @param roomId 채팅방 ID
     * @param userId 퇴장할 사용자 ID
     */
    @Transactional
    public void leaveGroupRoom(String roomId, String userId) {
        log.debug("[그룹방] 퇴장 시작 | roomId: {}, userId: {}", roomId, userId);

        // 방 존재 + GROUP 타입 확인
        ChatRoom room = chatRoomRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));
        if (room.getRoomType() != ChatRoom.RoomType.GROUP) {
            throw new StriderException(StriderErrorCodes.BAD_REQUEST);
        }

        // 멤버 레코드 조회 + soft-delete
        ChatRoomMember member = chatRoomMemberRepository
                .findByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.FORBIDDEN));

        member.setDeleted(true);
        member.setDeletedAt(LocalDateTime.now());

        // 남은 멤버 확인 → 0명이면 방도 soft-delete
        int remainingMembers = chatRoomMemberRepository.countByRoomIdAndIsDeletedFalse(roomId);
        if (remainingMembers == 0) {
            room.setDeleted(true);
            room.setDeletedAt(LocalDateTime.now());
            log.info("[그룹방] 마지막 멤버 퇴장 → 방 삭제 | roomId: {}", roomId);
        }

        log.info("[그룹방] 퇴장 완료 | roomId: {}, userId: {}", roomId, userId);
    }

    // ───────────────────────────────────────────────────────────────
    // 채팅방 멤버 목록 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 채팅방 멤버 목록 조회
     *
     * @param roomId 채팅방 ID
     * @param userId 요청자 ID (멤버 확인용)
     * @return 활성 멤버 목록
     */
    public List<ChatRoomMemberResponse> getRoomMembers(String roomId, String userId) {
        // 요청자 멤버 확인
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        return chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(roomId)
                .stream()
                .map(ChatRoomMemberResponse::from)
                .toList();
    }

    // ───────────────────────────────────────────────────────────────
    // 채팅방 목록 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 사용자가 참여 중인 채팅방 목록 조회
     *
     * ■ 응답에 포함되는 정보:
     *   - 채팅방 기본 정보 (roomId, roomType, createdAt)
     *   - 상대방 userId (DIRECT 방 기준)
     *   - 마지막 메시지 미리보기 (content, type, senderId, createdAt)
     *     사진·동영상은 내용이 비어 있으므로 안내 문구로 대체 (MessagePreviewUtils)
     *   - 안 읽은 메시지 수
     *
     * ■ 정렬:
     *   마지막 메시지 시각 기준 내림차순 (최근 대화가 위에)
     *   메시지 없는 방은 방 생성 시각 기준
     *
     * @param userId 조회 요청 사용자 ID
     * @return 참여 중인 채팅방 목록
     */
    public List<ChatRoomListResponse> getChatRooms(String userId) {
        // 사용자가 멤버인 모든 채팅방 멤버십 조회
        List<ChatRoomMember> memberships = chatRoomMemberRepository.findByUserIdAndIsDeletedFalse(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<String> roomIds = memberships.stream()
                .map(ChatRoomMember::getRoomId)
                .toList();

        // 채팅방 정보 일괄 조회
        Map<String, ChatRoom> roomMap = chatRoomRepository.findByRoomIdInAndIsDeletedFalse(roomIds)
                .stream()
                .collect(Collectors.toMap(ChatRoom::getRoomId, Function.identity()));

        // 전체 멤버 일괄 조회 — 상대방 userId 추출용
        Map<String, List<ChatRoomMember>> membersMap = chatRoomMemberRepository.findByRoomIdInAndIsDeletedFalse(roomIds)
                .stream()
                .collect(Collectors.groupingBy(ChatRoomMember::getRoomId));

        // 마지막 메시지 일괄 조회
        Map<String, Message> lastMessageMap = messageRepository.findLastMessagesByRoomIdIn(roomIds)
                .stream()
                .collect(Collectors.toMap(
                        Message::getRoomId,
                        Function.identity(),
                        (a, b) -> a  // 동일 createdAt이므로 먼저 온 것 유지
                ));

        // 안 읽은 메시지 수 일괄 조회
        Map<String, Long> unreadCountMap = messageRepository.countUnreadMessagesByRoomIdIn(roomIds, userId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue()
                ));

        // IMAGE 타입 마지막 메시지의 이미지 장수 일괄 조회 ("사진을 n장 보냈습니다" 미리보기용)
        // VIDEO는 항상 1개, TEXT는 미디어가 없으므로 IMAGE만 조회 대상
        List<String> imageMessageIds = lastMessageMap.values().stream()
                .filter(m -> m.getMessageType() == MessageType.IMAGE)
                .map(Message::getMessageId)
                .toList();
        Map<String, Integer> imageCountMap = imageMessageIds.isEmpty() ? Map.of()
                : messageMediaRepository.countByMessageIdIn(imageMessageIds).stream()
                        .collect(Collectors.toMap(
                                row -> (String) row[0],
                                row -> ((Number) row[1]).intValue()
                        ));

        // 응답 조합 및 마지막 메시지 시각 기준 내림차순 정렬
        return memberships.stream()
                .map(membership -> {
                    String roomId = membership.getRoomId();
                    ChatRoom room = roomMap.get(roomId);
                    if (room == null) {
                        return null;
                    }

                    // DIRECT 방: 상대방 userId, GROUP 방: 채팅방 이름
                    String otherUserId = null;
                    String roomName = null;
                    if (room.getRoomType() == ChatRoom.RoomType.DIRECT) {
                        List<ChatRoomMember> roomMembers = membersMap.getOrDefault(roomId, List.of());
                        otherUserId = roomMembers.stream()
                                .map(ChatRoomMember::getUserId)
                                .filter(id -> !id.equals(userId))
                                .findFirst()
                                .orElse(null);
                    } else {
                        roomName = room.getRoomName();
                    }

                    Message lastMessage = lastMessageMap.get(roomId);
                    long unreadCount = unreadCountMap.getOrDefault(roomId, 0L);
                    int memberCount = membersMap.getOrDefault(roomId, List.of()).size();

                    // messageType 컬럼 추가 이전에 쌓인 행은 값이 비어 있을 수 있어 TEXT로 간주
                    MessageType lastMessageType = lastMessage == null ? null
                            : lastMessage.getMessageType() != null ? lastMessage.getMessageType()
                            : MessageType.TEXT;

                    int lastMessageMediaCount = lastMessage == null ? 0
                            : imageCountMap.getOrDefault(lastMessage.getMessageId(), 0);

                    return ChatRoomListResponse.builder()
                            .roomId(roomId)
                            .roomType(room.getRoomType().name())
                            .otherUserId(otherUserId)
                            .roomName(roomName)
                            .lastMessageContent(lastMessage != null
                                    ? MessagePreviewUtils.of(lastMessageType, lastMessage.getContent(), lastMessageMediaCount)
                                    : null)
                            .lastMessageType(lastMessageType != null ? lastMessageType.name() : null)
                            .lastMessageSenderId(lastMessage != null ? lastMessage.getSenderId() : null)
                            .lastMessageAt(lastMessage != null ? lastMessage.getCreatedAt() : null)
                            .unreadCount(unreadCount)
                            .memberCount(memberCount)
                            .createdAt(room.getCreatedAt())
                            .build();
                })
                .filter(response -> response != null)
                .sorted((a, b) -> {
                    LocalDateTime timeA = a.getLastMessageAt() != null ? a.getLastMessageAt() : a.getCreatedAt();
                    LocalDateTime timeB = b.getLastMessageAt() != null ? b.getLastMessageAt() : b.getCreatedAt();
                    return timeB.compareTo(timeA);
                })
                .toList();
    }

    // ───────────────────────────────────────────────────────────────
    // 메시지 단건 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 메시지 단건 조회
     *
     */
    public MessageResponse getMessage(String messageId) {
        Message message = messageRepository
                .findByMessageIdAndIsDeletedFalse(messageId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));
                
        List<MediaResponse> media = messageMediaRepository
                .findByMessageIdAndIsDeletedFalseOrderBySortOrderAsc(messageId)
                .stream().map(MediaResponse::from).toList();

        return MessageResponse.from(message, 0, media);
    }

    // ───────────────────────────────────────────────────────────────
    // 메시지 이력 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 채팅방 메시지 이력 조회 (커서 기반 페이지네이션)
     *
     * ■ 가이드 규칙 7번: isFirstSearch() 헬퍼로 최초/커서 조회 분기
     * ■ 가이드 규칙 8-2: createdAt DESC + messageId DESC 복합 커서
     *
     * ■ direction 파라미터:
     *   - AFTER: 커서 기준 이후 메시지 (아래로 스크롤) — ASC 정렬
     *   - null 또는 BEFORE: 커서 기준 이전 메시지 (위로 스크롤) — DESC 정렬
     *
     * @param roomId          채팅방 ID
     * @param userId          조회 요청 사용자 (멤버 여부 확인)
     * @param size            가져올 메시지 수
     * @param cursorCreatedAt 이전 페이지 마지막 메시지 createdAt
     * @param cursorId        이전 페이지 마지막 메시지 messageId
     * @param direction       스크롤 방향 (AFTER 또는 BEFORE/null)
     * @return 메시지 목록
     */
    public List<MessageResponse> getMessages(
            String roomId,
            String userId,
            int size,
            LocalDateTime cursorCreatedAt,
            String cursorId,
            ScrollDirection direction
    ) {
        // 멤버가 아니면 대화 이력 조회 불가 (Notion 예외: "멤버 X → 403")
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        PageRequest pageable = PageRequest.of(0, size);

        List<Message> messages;

        if (isFirstSearch(cursorCreatedAt, cursorId)) {
            // 최초 조회: 최신 메시지부터 (DESC)
            messages = messageRepository.findMessagesFirst(roomId, pageable);
        } else if (direction == ScrollDirection.AFTER) {
            // 아래로 스크롤: 커서 이후 메시지 (ASC)
            messages = messageRepository.findMessagesByCursorAfter(roomId, cursorCreatedAt, cursorId, pageable);
        } else {
            // 위로 스크롤: 커서 이전 메시지 (DESC)
            messages = messageRepository.findMessagesAfterCursor(roomId, cursorCreatedAt, cursorId, pageable);
        }

        List<ChatRoomMember> members = chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(roomId);

        return toResponseWithUnread(messages, members);
    }

    // ───────────────────────────────────────────────────────────────
    // 미디어 갤러리 조회
    // ───────────────────────────────────────────────────────────────

    /**
     * 채팅방 미디어 갤러리 조회 (커서 기반 페이지네이션)
     *
     * ■ 가이드 규칙 7번: isFirstSearch() 헬퍼로 최초/커서 조회 분기
     * ■ 가이드 규칙 8-2: createdAt DESC + mediaId DESC 복합 커서
     *   같은 메시지로 보낸 여러 장은 createdAt이 동일할 수 있어 mediaId로 안정 정렬한다.
     *
     * ■ 단방향(DESC) 전용 — direction 파라미터를 받지 않는 이유:
     *   갤러리는 "최신 → 과거로 더 불러오기" 한 방향뿐이다. 커서보다 엄격히 과거인 것만
     *   반환하므로 페이지 간 중복이 없고, 클라이언트는 받은 페이지를 기존 목록 뒤에
     *   append 하면 위쪽에 이미 로딩된 항목이 그대로 유지된다.
     *   갤러리를 열어둔 채 새 미디어가 올라오는 경우는 /sub/chat/room/{roomId} broadcast를
     *   받아 클라이언트가 맨 앞에 prepend 한다.
     *
     * @param roomId          채팅방 ID
     * @param userId          조회 요청 사용자 (멤버 여부 확인)
     * @param size            가져올 미디어 수
     * @param cursorCreatedAt 이전 페이지 마지막 미디어 createdAt
     * @param cursorId        이전 페이지 마지막 미디어 mediaId
     * @return 최신순 미디어 목록
     */
    public List<MediaResponse> getRoomMedia(
            String roomId,
            String userId,
            int size,
            LocalDateTime cursorCreatedAt,
            String cursorId
    ) {
        // 멤버가 아니면 갤러리 조회 불가 (Notion 예외: "멤버 X → 403")
        if (!chatRoomMemberRepository.existsByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)) {
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        PageRequest pageable = PageRequest.of(0, size);

        List<MessageMedia> mediaList = isFirstSearch(cursorCreatedAt, cursorId)
                // 최초 조회: 최신 미디어부터 (DESC)
                ? messageMediaRepository.findGalleryFirst(roomId, pageable)
                // 더 보기: 커서 이전(과거) 미디어 (DESC)
                : messageMediaRepository.findGalleryAfterCursor(roomId, cursorCreatedAt, cursorId, pageable);

        return mediaList.stream().map(MediaResponse::from).toList();
    }

    /**
     * 채팅방 입장 시 안 읽은 메시지 기준 양방향 조회
     *
     * ■ 동작:
     *   lastReadAt 기준으로 이전 메시지(컨텍스트) + 이후 메시지(안 읽은 것)를 함께 반환
     *   member.createdAt(참여 시점) 이전 메시지는 제외하여 초대 전 대화 노출 방지
     *
     * ■ lastReadAt == null (첫 방문):
     *   pivot = member.createdAt → before는 빈 리스트, after(참여 이후 메시지)만 반환
     *
     * @param roomId 채팅방 ID
     * @param userId 입장한 사용자 ID
     * @param size   각 방향별 가져올 메시지 수
     * @return 안 읽은 메시지 기준 양방향 메시지 + 안 읽은 수 + 마지막 읽은 메시지 ID
     */
    public UnreadMessagesResponse getMessagesAroundUnread(String roomId, String userId, int size) {
        ChatRoomMember member = chatRoomMemberRepository
                .findByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.FORBIDDEN));

        LocalDateTime joinedAt = member.getCreatedAt();
        // lastReadAt이 null이면 참여 시점을 기준으로 사용
        LocalDateTime pivot = member.getLastReadAt() != null ? member.getLastReadAt() : joinedAt;

        PageRequest pageable = PageRequest.of(0, size);

        // 기준 시점 이전 메시지 (컨텍스트) — DESC 조회 후 reverse
        List<Message> beforeMessages = messageRepository.findMessagesBefore(roomId, pivot, joinedAt, pageable);
        List<Message> beforeReversed = new ArrayList<>(beforeMessages);
        Collections.reverse(beforeReversed);

        // 기준 시점 이후 메시지 (안 읽은 메시지) — ASC 조회
        List<Message> afterMessages = messageRepository.findMessagesAfter(roomId, pivot, pageable);

        // before + after 합쳐서 시간순 리스트 구성
        List<ChatRoomMember> members = chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(roomId);
        List<Message> allMessages = Stream.concat(beforeReversed.stream(), afterMessages.stream()).toList();
        List<MessageResponse> messages = toResponseWithUnread(allMessages, members);

        // 마지막으로 읽은 메시지 ID (before의 가장 최근 메시지)
        String lastReadMessageId = beforeReversed.isEmpty()
                ? null
                : beforeReversed.get(beforeReversed.size() - 1).getMessageId();

        return UnreadMessagesResponse.builder()
                .messages(messages)
                .unreadCount(afterMessages.size())
                .lastReadMessageId(lastReadMessageId)
                .build();
    }

    // ───────────────────────────────────────────────────────────────
    // 읽음 처리
    // ───────────────────────────────────────────────────────────────

    /**
     * 채팅방 입장 시 읽음 처리
     *
     * ■ 방식:
     *   ChatRoomMember.lastReadAt을 현재 시각으로 갱신
     *   → lastReadAt 이후 도착한 메시지만 unread로 계산
     *   → 입장 이후 도착한 메시지는 자동으로 unread 상태 유지
     *
     * @param roomId 채팅방 ID
     * @param userId 입장한 사용자 ID
     */
    @Transactional
    public LocalDateTime markMessagesAsRead(String roomId, String userId) {
        log.debug("[읽음 처리] 시작 | roomId: {}, userId: {}", roomId, userId);

        ChatRoomMember member = chatRoomMemberRepository
                .findByRoomIdAndUserIdAndIsDeletedFalse(roomId, userId)
                .orElseThrow(() -> new StriderException(StriderErrorCodes.FORBIDDEN));

        LocalDateTime now = LocalDateTime.now();
        member.setLastReadAt(now);

        log.info("[읽음 처리] 완료 | roomId: {}, userId: {}", roomId, userId);
        return now;
    }

    // ───────────────────────────────────────────────────────────────
    // 메시지 전송 (Outbox 패턴 적용)
    // ───────────────────────────────────────────────────────────────

    /**
     * 메시지 전송 처리
     *
     * ■ Outbox 패턴 적용:
     *   Message 저장과 Outbox 저장을 같은 @Transactional로 묶음
     *   → 둘 다 성공 or 둘 다 롤백 → Kafka 발행 누락 없음
     *
     * ■ 처리 흐름:
     *   1. 채팅방 존재 확인
     *   2. 발신자 멤버 권한 확인
     *   3. 메시지 구성 검증 (TEXT / IMAGE / VIDEO 중 하나)
     *   4. Message 저장 + MessageMedia 저장 + Outbox 저장 (같은 트랜잭션)
     *   → 이후 OutboxScheduler → Kafka 발행 → ChatMessageConsumer → STOMP broadcast
     *
     * @param request roomId + senderId + content
     */
    @Transactional  // Message 저장 + MessageMedia 저장 + Outbox 저장 원자성 보장
    public void sendMessage(MessageSendRequest request) {
        log.debug("[메시지] 전송 시작 | roomId: {}, sender: {}", request.getRoomId(), request.getSenderId());

        // ── Step 1: 채팅방 존재 확인 ──────────────────────────────
        chatRoomRepository.findByRoomIdAndIsDeletedFalse(request.getRoomId())
                .orElseThrow(() -> new StriderException(StriderErrorCodes.NOT_FOUND));

        // ── Step 2: 발신자 멤버 권한 확인 ────────────────────────
        boolean isMember = chatRoomMemberRepository
                .existsByRoomIdAndUserIdAndIsDeletedFalse(request.getRoomId(), request.getSenderId());

        if (!isMember) {
            log.warn("[메시지] 멤버 아님 | roomId: {}, userId: {}", request.getRoomId(), request.getSenderId());
            throw new StriderException(StriderErrorCodes.FORBIDDEN);
        }

        // ── Step 3: 멱등성 사전 체크 (미디어 중복 저장 방지) ──────
        // DB UNIQUE 제약만으로는 자식 MessageMedia 행 중복을 막을 수 없어 명시 체크
        if (request.getClientMessageId() != null) {
            Optional<Message> existing = messageRepository
                    .findByRoomIdAndClientMessageIdAndIsDeletedFalse(
                            request.getRoomId(), request.getClientMessageId());
            if (existing.isPresent()) {
                log.debug("[메시지] 중복 전송 감지, 무시 | clientMessageId: {}", request.getClientMessageId());
                return;
            }
        }

        // ── Step 4: 메시지 구성 검증 + messageType 결정 ───────────
        List<MediaItem> mediaItems = request.getMedia() != null ? request.getMedia() : Collections.emptyList();
        validateMessageComposition(request, mediaItems);

        // validateMessageComposition에서 동질성이 보장되므로 첫 아이템만 확인하면 된다
        MessageType messageType = mediaItems.isEmpty() ? MessageType.TEXT
                : mediaItems.get(0).getMediaType() == MessageMedia.MediaType.IMAGE
                ? MessageType.IMAGE : MessageType.VIDEO;

        // content가 null이면 빈 문자열로 (NOT NULL 컬럼 충족)
        String content = request.getContent() != null ? request.getContent() : "";

        // ── Step 5: Message 저장 ───────────────────────────────────
        Message message = Message.builder()
                .roomId(request.getRoomId())
                .senderId(request.getSenderId())
                .content(content)
                .messageType(messageType)
                .clientMessageId(request.getClientMessageId())
                .build();

        Message savedMessage = messageRepository.save(message);
        log.debug("[메시지] DB 저장 완료 | messageId: {}", savedMessage.getMessageId());

        // ── Step 6: MessageMedia 저장 ─────────────────────────────
        List<MessageMedia> savedMediaList = Collections.emptyList();
        if (!mediaItems.isEmpty()) {
            List<MessageMedia> mediaEntities = new ArrayList<>();
            for (int i = 0; i < mediaItems.size(); i++) {
                MediaItem item = mediaItems.get(i);
                mediaEntities.add(MessageMedia.builder()
                        .messageId(savedMessage.getMessageId())
                        .roomId(request.getRoomId())
                        .senderId(request.getSenderId())
                        .mediaType(MessageMedia.MediaType.valueOf(item.getMediaType().name()))
                        .originalUrl(item.getOriginalUrl())
                        .originalS3Key(item.getOriginalS3Key())
                        .thumbnailUrl(item.getThumbnailUrl())
                        .thumbnailS3Key(item.getThumbnailS3Key())
                        .previewUrl(item.getPreviewUrl())
                        .previewS3Key(item.getPreviewS3Key())
                        .sortOrder(i)
                        .build());
            }
            savedMediaList = messageMediaRepository.saveAll(mediaEntities);
            log.debug("[메시지] 미디어 저장 완료 | 건수: {}", savedMediaList.size());
        }

        // ── Step 7: Outbox 저장 (같은 트랜잭션) ──────────────────
        ChatMessageEvent event = ChatMessageEvent.from(savedMessage, savedMediaList);
        String payload = serializeToJson(event);

        Outbox outbox = Outbox.builder()
                .aggregateId(savedMessage.getMessageId())
                .eventType(ChatEventType.CHAT_MESSAGE_CREATED.name())
                .payload(payload)
                .build();

        outboxRepository.save(outbox);
        log.debug("[메시지] Outbox 저장 완료 | outboxId: {}", outbox.getOutboxId());
    }

    /**
     * 공지 등록 알림을 채팅 스트림에 시스템 메시지로 남긴다
     *
     * ■ 호출부(NoticeService)의 트랜잭션에 반드시 참여한다(MANDATORY):
     *   공지 행 저장과 이 메시지 저장이 한 트랜잭션이어야 "공지는 걸렸는데 말풍선은
     *   없는" 상태나 그 반대가 생기지 않는다. 트랜잭션 없이 호출하면 즉시 실패한다.
     *
     * ■ 일반 메시지와 같은 Outbox → Kafka → broadcast 경로를 탄다:
     *   읽음 수 계산·푸시 알림·채팅방 목록 갱신이 전부 기존 컨슈머에서 처리된다.
     *   따로 broadcast하면 이 로직을 전부 다시 만들어야 한다.
     *
     * ■ content에는 공지로 걸린 원본 메시지 내용이 들어간다:
     *   "공지가 등록되었습니다." 머리말과 "글 확인하기" 링크는 서버가 만들지 않는다.
     *   클라이언트가 messageType == NOTICE 를 보고 UI에서 붙이는 것이고, 서버는
     *   그 아래 보여줄 원본 내용만 담는다.
     *   "글 확인하기"는 noticeId가 가리키는 공지 상세 페이지로 이동한다.
     *
     * ■ 공지는 텍스트만 걸 수 있으므로 말풍선도 항상 텍스트다:
     *   원본 유형을 따로 실어 보낼 필요가 없다. 말풍선에 사진이 붙는 경우도 없다.
     *
     * ■ 이 말풍선은 공지 읽음 수의 기준이기도 하다:
     *   공지 상세의 "읽은 사람 수"는 이 메시지를 읽은 멤버 수로 센다.
     *   그래서 호출부는 반환된 messageId를 공지 행에 연결해 두어야 한다.
     *
     * @param roomId   채팅방 ID
     * @param senderId 공지를 등록한 userId (말풍선 발신자, 읽음수 계산에서 제외됨)
     * @param content  공지로 걸린 원본 메시지 내용 (RoomNotice.content와 같은 스냅샷)
     * @param noticeId 이 말풍선이 가리키는 공지 ID ("글 확인하기" 이동 대상)
     * @return 저장된 시스템 메시지
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Message sendNoticeRegisteredMessage(
            String roomId, String senderId, String content, String noticeId) {

        Message saved = messageRepository.save(Message.builder()
                .roomId(roomId)
                .senderId(senderId)
                .content(content)
                .messageType(MessageType.NOTICE)
                .noticeId(noticeId)
                .build());

        outboxRepository.save(Outbox.builder()
                .aggregateId(saved.getMessageId())
                .eventType(ChatEventType.CHAT_MESSAGE_CREATED.name())
                .payload(serializeToJson(ChatMessageEvent.from(saved)))
                .build());

        log.info("[공지] 등록 알림 메시지 저장 | roomId: {}, messageId: {}, noticeId: {}",
                roomId, saved.getMessageId(), noticeId);

        return saved;
    }

    /**
     * 메시지 구성 검증 — 한 메시지는 TEXT / IMAGE / VIDEO 중 정확히 하나
     *
     * ■ 정책:
     *   TEXT   : content만, media 없음
     *   IMAGE  : 이미지 1~{@value #MAX_IMAGE_COUNT}장, content 없음
     *   VIDEO  : 비디오 1개, content 없음
     *   → 캡션(content + media 동시 전송)은 불가. 텍스트는 별도 메시지로 보낸다.
     *
     * 여기서 막지 않으면 mediaType null은 NPE, originalUrl/originalS3Key null은
     * NOT NULL 컬럼 위반으로 500이 된다. 클라이언트 잘못이므로 400으로 응답한다.
     */
    private void validateMessageComposition(MessageSendRequest request, List<MediaItem> mediaItems) {
        String roomId = request.getRoomId();
        String senderId = request.getSenderId();

        boolean hasContent = request.getContent() != null && !request.getContent().isBlank();
        boolean hasMedia = !mediaItems.isEmpty();

        if (!hasContent && !hasMedia) {
            throw rejectMessage(roomId, senderId, "내용이 비어 있습니다");
        }
        if (hasContent && hasMedia) {
            throw rejectMessage(roomId, senderId, "미디어에 텍스트를 함께 보낼 수 없습니다. 텍스트는 별도 메시지로 보내주세요");
        }
        if (!hasMedia) return;  // TEXT 확정

        for (MediaItem item : mediaItems) {
            if (item.getMediaType() == null
                    || isBlank(item.getOriginalUrl())
                    || isBlank(item.getOriginalS3Key())) {
                throw rejectMessage(roomId, senderId, "미디어 필수 항목(mediaType·originalUrl·originalS3Key)이 누락되었습니다");
            }
        }

        MessageMedia.MediaType firstType = mediaItems.get(0).getMediaType();
        boolean homogeneous = mediaItems.stream().allMatch(m -> m.getMediaType() == firstType);
        if (!homogeneous) {
            throw rejectMessage(roomId, senderId, "이미지와 동영상을 함께 보낼 수 없습니다");
        }

        if (firstType == MessageMedia.MediaType.VIDEO && mediaItems.size() > 1) {
            throw rejectMessage(roomId, senderId,
                    "동영상은 1개만 보낼 수 있습니다 (요청: " + mediaItems.size() + "개)");
        }
        if (firstType == MessageMedia.MediaType.IMAGE && mediaItems.size() > MAX_IMAGE_COUNT) {
            throw rejectMessage(roomId, senderId,
                    "이미지는 최대 " + MAX_IMAGE_COUNT + "장까지 보낼 수 있습니다 (요청: " + mediaItems.size() + "장)");
        }
    }

    private StriderException rejectMessage(String roomId, String senderId, String reason) {
        log.warn("[메시지] 검증 실패 — {} | roomId: {}, sender: {}", reason, roomId, senderId);
        return new StriderException(StriderErrorCodes.BAD_REQUEST, reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ───────────────────────────────────────────────────────────────
    // Private 헬퍼
    // ───────────────────────────────────────────────────────────────

    /**
     * 커서 유무 판별 헬퍼
     */
    private boolean isFirstSearch(LocalDateTime cursorCreatedAt, String cursorId) {
        return cursorCreatedAt == null || cursorId == null;
    }

    /**
     * ChatMessageEvent → unreadCount 포함 MessageResponse 변환
     * Kafka Consumer에서 STOMP broadcast 시 사용
     */
    public MessageResponse buildResponseWithUnread(ChatMessageEvent event) {
        List<ChatRoomMember> members = chatRoomMemberRepository.findByRoomIdAndIsDeletedFalse(event.getRoomId());

        // 발신자 제외 + 현재 해당 채팅방을 보고 있는 사람 제외 (이미 읽은 것으로 처리)
        // preExcludedUserIds: 클라이언트가 이중 차감을 방지하기 위해 사용
        List<String> preExcludedUserIds = members.stream()
                .filter(m -> !m.getUserId().equals(event.getSenderId()))
                .filter(m -> event.getRoomId().equals(userPresenceService.getActiveRoom(m.getUserId())))
                .map(ChatRoomMember::getUserId)
                .collect(Collectors.toList());

        int unreadCount = (int) members.stream()
                .filter(m -> !m.getUserId().equals(event.getSenderId()))
                .filter(m -> !event.getRoomId().equals(userPresenceService.getActiveRoom(m.getUserId())))
                .count();

        List<MediaResponse> media = event.getMedia() == null ? Collections.emptyList() :
                event.getMedia().stream()
                        .map(p -> MediaResponse.builder()
                                .mediaId(p.getMediaId())
                                .messageId(event.getMessageId())
                                .senderId(event.getSenderId())
                                .mediaType(p.getMediaType())
                                .originalUrl(p.getOriginalUrl())
                                .originalS3Key(p.getOriginalS3Key())
                                .thumbnailUrl(p.getThumbnailUrl())
                                .thumbnailS3Key(p.getThumbnailS3Key())
                                .previewUrl(p.getPreviewUrl())
                                .previewS3Key(p.getPreviewS3Key())
                                .sortOrder(p.getSortOrder())
                                .createdAt(p.getCreatedAt())
                                .build())
                        .toList();

        return MessageResponse.builder()
                .messageId(event.getMessageId())
                .roomId(event.getRoomId())
                .senderId(event.getSenderId())
                .content(event.getContent())
                .messageType(event.getMessageType())
                .noticeId(event.getNoticeId())
                .media(media)
                .clientMessageId(event.getClientMessageId())
                .createdAt(event.getCreatedAt() != null ? event.getCreatedAt() : LocalDateTime.now())
                .unreadCount(unreadCount)
                .preExcludedUserIds(preExcludedUserIds)
                .build();
    }

    /**
     * 메시지별 안 읽은 멤버 수 계산
     *
     * 멤버 목록을 한 번 조회한 뒤, 각 메시지의 createdAt과 비교하여
     * lastReadAt < message.createdAt인 멤버 수를 계산 (발신자 제외)
     *
     * @param message 대상 메시지
     * @param members 채팅방 멤버 목록
     * @return 안 읽은 멤버 수
     */
    private int calculateUnreadCount(Message message, List<ChatRoomMember> members) {
        return (int) members.stream()
                .filter(m -> !m.getUserId().equals(message.getSenderId()))
                .filter(m -> m.getLastReadAt() == null || m.getLastReadAt().isBefore(message.getCreatedAt()))
                .count();
    }

    /**
     * 메시지 목록을 unreadCount + 미디어가 포함된 MessageResponse 목록으로 변환
     */
    private List<MessageResponse> toResponseWithUnread(List<Message> messages, List<ChatRoomMember> members) {
        if (messages.isEmpty()) return Collections.emptyList();

        List<String> messageIds = messages.stream().map(Message::getMessageId).toList();
        Map<String, List<MediaResponse>> mediaByMessageId = messageMediaRepository
                .findByMessageIdInAndIsDeletedFalseOrderBySortOrderAsc(messageIds)
                .stream()
                .collect(Collectors.groupingBy(
                        MessageMedia::getMessageId,
                        Collectors.mapping(MediaResponse::from, Collectors.toList())
                ));

        return messages.stream()
                .map(msg -> MessageResponse.from(
                        msg,
                        calculateUnreadCount(msg, members),
                        mediaByMessageId.getOrDefault(msg.getMessageId(), Collections.emptyList())
                ))
                .toList();
    }

    /**
     * ChatMessageEvent를 JSON 문자열로 직렬화
     * 실패 시 StriderException(INTERNAL_SERVER_ERROR) 던져 트랜잭션 롤백 유도
     *
     * @param event 직렬화할 이벤트
     * @return JSON 문자열
     */
    private String serializeToJson(ChatMessageEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[메시지] Outbox 페이로드 직렬화 실패 | messageId: {}", event.getMessageId(), e);
            throw new StriderException(StriderErrorCodes.INTERNAL_SERVER_ERROR);
        }
    }
}
