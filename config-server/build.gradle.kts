plugins {
    id("iseonjwa.spring-boot-service")
}

dependencies {
    implementation(libs.spring.cloud.config.server)
}

// ./gradlew :config-server:bootRun 으로 띄울 때는 저장소의 config-repo를 읽는다
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment("CONFIG_REPO_LOCATION", "file:${rootDir.resolve("config-repo").invariantSeparatorsPath}/")
}

