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

// 테스트가 config-repo 파일을 읽으므로 입력으로 등록한다.
// 등록하지 않으면 설정 파일만 바뀌었을 때 Gradle이 테스트를 최신 상태로 보고 건너뛴다
tasks.named<Test>("test") {
    inputs.dir(rootDir.resolve("config-repo")).withPropertyName("configRepo")
}
