# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx512m

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app
WORKDIR /app
ARG APP_MODULE
COPY --from=builder --chown=app:app /workspace/${APP_MODULE}/build/libs/app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
