// PostgreSQL을 쓰는 서비스(user, reservation, payment). 스키마는 Flyway 마이그레이션으로만 바꾼다
plugins {
    id("iseonjwa.mvc-service")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    "implementation"(lib("spring-boot-starter-data-jpa"))
    "implementation"(lib("spring-boot-starter-flyway"))
    // Flyway 10부터 DB별 지원이 별도 모듈로 나뉘었다. 없으면 PostgreSQL에 연결하지 못한다
    "implementation"(lib("flyway-database-postgresql"))
    "runtimeOnly"(lib("postgresql"))

    // 테스트는 실제 PostgreSQL 컨테이너에 붙는다(H2는 부분 인덱스 같은 PostgreSQL 문법을 못 잡는다)
    "testImplementation"(lib("spring-boot-testcontainers"))
    "testImplementation"(lib("testcontainers-postgresql"))
    "testImplementation"(lib("testcontainers-junit"))
}
