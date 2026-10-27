plugins {
    id("iseonjwa.spring-boot-service")
}

dependencies {
    implementation(libs.spring.cloud.starter.eureka.server)
    implementation(libs.spring.cloud.starter.config)
}
