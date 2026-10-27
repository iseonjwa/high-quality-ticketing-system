pluginManagement {
    // 모듈 공통 빌드 설정(컨벤션 플러그인)은 build-logic에 둔다
    includeBuild("build-logic")
}

plugins {
    // 빌드에 필요한 JDK 21이 PC에 없으면 Gradle이 내려받는다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "high-quality-ticketing-system"

// 공통 라이브러리
include(
    "common",
    "common-response",
    "common-web",
    "common-messaging",
)

// 실행 서비스
include(
    "config-server",
)
