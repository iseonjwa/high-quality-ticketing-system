// Spring MVC로 요청을 받는 서비스(user, reservation, payment, mock-pg). Gateway(WebFlux)는 쓰지 않는다
plugins {
    id("iseonjwa.spring-boot-service")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(lib("spring-boot-starter-webmvc"))
    "implementation"(lib("spring-boot-starter-validation"))
    // 설정은 Config 서버에서 받는다 (SA 9-6)
    "implementation"(lib("spring-cloud-starter-config"))
    "testImplementation"(lib("spring-boot-starter-webmvc-test"))
}
