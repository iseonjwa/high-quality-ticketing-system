// 단독으로 실행되는 서비스(gateway, eureka, config-server, user-service 등). 실행 가능한 jar(bootJar)를 만든다
plugins {
    id("iseonjwa.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    // 모든 서비스가 Prometheus 지표와 Zipkin 추적을 내보낸다 (M1 완료 기준: Prometheus·Zipkin에서 보인다)
    "implementation"(lib("spring-boot-starter-actuator"))
    "runtimeOnly"(lib("micrometer-registry-prometheus"))
    "implementation"(lib("spring-boot-starter-zipkin"))
}

// 컨테이너 이미지에는 실행 jar 하나만 넣는다. 의존성 없는 plain jar는 만들지 않는다
tasks.named<Jar>("jar") { enabled = false }
