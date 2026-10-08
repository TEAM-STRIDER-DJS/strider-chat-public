package com.strider.chat.kafka.event;

/**
 * 채팅 이벤트 타입
 *
 * ■ 역할:
 *   Kafka 이벤트 및 Outbox의 eventType을 타입 안전하게 관리.
 *   문자열 하드코딩 방지 → 오타/불일치 컴파일 타임에 검출.
 *
 * ■ 확장:
 *   새로운 이벤트 추가 시 여기에 enum 값 추가
 */
public enum ChatEventType {

    /** 메시지 생성 이벤트 */
    CHAT_MESSAGE_CREATED,

    /** 메시지 삭제 이벤트 (추후 구현) */
    CHAT_MESSAGE_DELETED,

    /** 메시지 읽음 이벤트 (추후 구현) */
    CHAT_MESSAGE_READ
}
