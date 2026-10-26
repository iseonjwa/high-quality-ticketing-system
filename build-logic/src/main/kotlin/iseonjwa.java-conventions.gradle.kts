// 모든 모듈이 공통으로 쓰는 Java 설정. 직접 적용하지 않고 java-library, spring-boot-service가 가져다 쓴다
plugins {
    java
}

group = "com.iseonjwa"

// 컨벤션 플러그인에서는 libs 접근자를 쓸 수 없어 카탈로그를 이름으로 찾는다
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

dependencies {
    // Boot·Cloud BOM으로 라이브러리 버전을 맞춘다. 어노테이션 처리기(Lombok 등)도 같은 버전을 쓰게 한다
    val bomConfigurations = listOf("implementation", "annotationProcessor", "testImplementation", "testAnnotationProcessor")
    bomConfigurations.forEach { configuration ->
        add(configuration, platform(lib("spring-boot-dependencies")))
        add(configuration, platform(lib("spring-cloud-dependencies")))
    }

    testImplementation(lib("spring-boot-starter-test"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 생성자·메서드 파라미터 이름을 바이트코드에 남긴다 (Spring이 이름으로 바인딩할 때 필요)
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
