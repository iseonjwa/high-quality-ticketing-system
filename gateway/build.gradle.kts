plugins {
    id("iseonjwa.spring-boot-service")
}

dependencies {
    implementation(libs.spring.cloud.starter.gateway.webflux)
    implementation(libs.spring.cloud.starter.eureka.client)
    implementation(libs.spring.cloud.starter.config)
}
