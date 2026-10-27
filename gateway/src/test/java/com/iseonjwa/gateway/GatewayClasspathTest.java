package com.iseonjwa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Gateway가 common을 의존해도 서블릿·JPA·Kafka 라이브러리를 받지 않는지 확인한다.
 * common/build.gradle에서 compileOnly를 implementation·api로 바꾸면 여기서 실패한다.
 */
class GatewayClasspathTest {

    // Note: DispatcherServlet이 있으면 Gateway가 MvcFoundOnClasspathException으로 멈추고,
    // Hibernate가 있으면 DataSource 자동 구성이 켜져 DB 설정 없이 기동하지 못한다.
    // 클래스 로딩(ClassUtils.isPresent)은 서블릿 API가 없으면 jar가 있어도 false라서 클래스 파일 존재로 확인한다
    @ParameterizedTest
    @ValueSource(strings = {
            "org/springframework/web/servlet/DispatcherServlet.class",
            "org/hibernate/SessionFactory.class",
            "org/springframework/kafka/core/KafkaTemplate.class"
    })
    void 게이트웨이_클래스패스에는_서블릿_JPA_Kafka가_없다(String classFile) {
        assertThat(getClass().getClassLoader().getResource(classFile)).isNull();
    }
}
