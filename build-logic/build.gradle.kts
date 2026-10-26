plugins {
    `kotlin-dsl`
}

dependencies {
    // 컨벤션 플러그인 안에서 org.springframework.boot 플러그인을 적용하려면 필요하다
    implementation(libs.spring.boot.gradle.plugin)
}
