# syntax=docker/dockerfile:1
# 모든 Spring 서비스가 같이 쓰는 이미지. compose에서 MODULE 인자로 어떤 모듈을 담을지 정한다
#   docker build --build-arg MODULE=config-server -t iseonjwa/config-server .

# 1단계: 컨테이너 안에서 Gradle로 모든 서비스의 실행 jar를 한 번에 만든다(PC에 JDK가 없어도 빌드된다)
# MODULE을 쓰지 않으므로 서비스 6개의 이 단계가 똑같아지고, BuildKit이 한 번만 실행해 나눠 쓴다.
# 서비스마다 따로 빌드하면 같은 Gradle 캐시를 동시에 잡다가 잠금 충돌이 난다
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY . .
# Gradle 캐시를 빌드 사이에 재사용한다. locked: 다른 빌드가 쓰는 동안은 기다린다
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    chmod +x gradlew && ./gradlew bootJar --no-daemon -x test -q \
    && mkdir /jars && cp */build/libs/*.jar /jars/

# 2단계: 실행에는 JRE와 해당 서비스 jar만 둔다(JDK·소스·Gradle 캐시를 이미지에서 뺀다)
FROM eclipse-temurin:21.0.12.1_1-jre-noble
ARG MODULE
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /jars/${MODULE}.jar app.jar
USER app
# 힙 상한은 compose의 JAVA_OPTS로 서비스마다 준다 (SA 9-3)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
