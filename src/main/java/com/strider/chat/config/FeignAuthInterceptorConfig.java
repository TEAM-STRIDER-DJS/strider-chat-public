package com.strider.chat.config;

import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 클라이언트 인증 헤더 전파 설정
 *
 * ■ 목적:
 *   현재 서비스로 들어온 HTTP 요청의 Authorization 헤더(JWT 토큰)를
 *   Feign으로 다른 서비스(예: strider-user-profile)를 호출할 때
 *   그대로 전달함.
 *
 * ■ 동작 흐름:
 *   클라이언트 → [Authorization: Bearer {JWT}] → strider-chat
 *   strider-chat → [Authorization: Bearer {JWT}] → strider-user-profile (Feign)
 *
 *   토큰 없이 Feign 호출하면 strider-user-profile에서 인증 실패(401)가 발생하므로
 *   반드시 헤더를 전파해야 함.
 */
@Slf4j
@Configuration
public class FeignAuthInterceptorConfig {

    /**
     * Feign 요청에 Authorization 헤더를 자동으로 추가하는 인터셉터 빈 등록
     *
     * @return RequestInterceptor — Feign 호출 시 자동 실행되는 인터셉터
     */
    @Bean
    public RequestInterceptor authorizationInterceptor() {
        return template -> {
            // 현재 처리 중인 HTTP 요청 컨텍스트 가져오기
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

            // WebSocket 등 서블릿 요청이 아닌 경우 헤더 전파 생략
            if (!(attributes instanceof ServletRequestAttributes sra)) return;

            // 현재 요청에서 Authorization 헤더 값 추출
            String authHeader = sra.getRequest().getHeader(HttpHeaders.AUTHORIZATION);

            // Authorization 헤더가 존재하는 경우에만 Feign 요청에 추가
            if (authHeader != null && !authHeader.isBlank()) {
                template.header(HttpHeaders.AUTHORIZATION, authHeader);
            }
        };
    }
}
