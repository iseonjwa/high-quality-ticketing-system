# syntax=docker/dockerfile:1
# 모든 Spring 서비스가 같이 쓰는 이미지. compose에서 MODULE 인자로 어떤 모듈을 담을지 정한다
#   docker build --build-arg MODULE=config-server -t iseonjwa/config-server .

# 1단계: 컨테이너 안에서 Gradle로 실행 jar를 만든다(PC에 JDK가 없어도 빌드된다)
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
ARG MODULE
WORKDIR /workspace
COPY . .
# Gradle 캐시를 빌드 사이에 재사용해 의존성을 매번 내려받지 않는다
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew ":${MODULE}:bootJar" --no-daemon -x test -q \
    && cp "${MODULE}/build/libs/${MODULE}.jar" /app.jar

# 2단계: 실행에는 JRE와 jar만 둔다(JDK·소스·Gradle 캐시를 이미지에서 뺀다)
FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# 힙 상한은 compose의 JAVA_OPTS로 서비스마다 준다 (SA 9-3)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
