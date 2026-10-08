package com.strider.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * strider-chat 서비스 진입점
 *
 * ■ @EnableScheduling:
 *   OutboxScheduler의 @Scheduled가 동작하려면 반드시 필요.
 *   스케줄러는 PENDING 상태의 Outbox를 주기적으로 읽어 Kafka에 발행.
 *
 * ■ 서비스 역할:
 *   - 포트: 8002
 *   - WebSocket(STOMP): 클라이언트 실시간 메시지 송수신
 *   - REST HTTP: 채팅방 생성, 메시지 이력 조회
 *   - Kafka: 메시지 이벤트 발행/소비 (Outbox 패턴)
 *   - PostgreSQL: 메시지, 채팅방, 멤버, Outbox 영구 저장
 */
@SpringBootApplication(scanBasePackages = {
        "com.strider.chat",
        "com.strider.strider_common_lib"
})
@ConfigurationPropertiesScan
@EnableFeignClients
@EnableScheduling  // OutboxScheduler 활성화
public class StriderChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(StriderChatApplication.class, args);
    }
}
