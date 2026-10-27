plugins {
    id("iseonjwa.jpa-postgres")
}

dependencies {
    implementation(libs.spring.cloud.starter.eureka.client)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.kafka)
}
