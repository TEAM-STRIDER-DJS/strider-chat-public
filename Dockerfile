FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# 컨테이너 기본 타임존은 UTC라 @CreationTimestamp(LocalDateTime)가 UTC로 기록된다.
# 로컬(macOS/KST) 개발 환경과 기준을 맞추기 위해 KST로 고정한다.
ENV TZ=Asia/Seoul

COPY app.jar /app/app.jar

EXPOSE 8002

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
