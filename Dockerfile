# syntax=docker/dockerfile:1
# 모든 Spring 앱이 함께 쓰는 이미지다. compose가 MODULE 인자로 빌드할 모듈을 정한다 (SA 9-1: eclipse-temurin:21-jre)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG MODULE
# Gradle 캐시를 이미지 빌드끼리 공유한다. 여러 이미지를 동시에 빌드하면 캐시 잠금이 겹치므로 locked로 순서대로 쓴다
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew \
    && ./gradlew ":${MODULE}:bootJar" --no-daemon --console=plain \
    && cp "${MODULE}"/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /app.jar app.jar
USER app
# 힙 상한은 compose의 JAVA_TOOL_OPTIONS로 서비스마다 준다 (SA 9-3)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
