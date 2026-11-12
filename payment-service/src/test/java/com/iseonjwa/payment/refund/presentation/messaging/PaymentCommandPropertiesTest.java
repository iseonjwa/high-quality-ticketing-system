package com.iseonjwa.payment.refund.presentation.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.TopicBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentCommandPropertiesTest {
    @Test
    void 기본값은_파티션_3과_동시성_3이다() {
        var properties = new PaymentCommandProperties(null, null);

        assertEquals(3, properties.partitions());
        assertEquals(3, properties.concurrency());
    }

    @Test
    void 파티션과_동시성은_1_이상이어야_한다() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentCommandProperties(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PaymentCommandProperties(3, 0));
    }

    @Test
    void 동시성이_파티션_수보다_크면_기동을_막는다() {
        var exception = assertThrows(IllegalArgumentException.class, () -> new PaymentCommandProperties(3, 4));

        assertEquals(true, exception.getMessage().contains("이하여야"));
        // 같거나 작으면 된다
        assertEquals(1, new PaymentCommandProperties(3, 1).concurrency());
        assertEquals(3, new PaymentCommandProperties(3, 3).concurrency());
    }

    @Test
    void 설정으로_바인딩하고_범위를_벗어나면_컨텍스트가_뜨지_않는다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);

        runner.withPropertyValues("payment.messaging.command.partitions=6", "payment.messaging.command.concurrency=2")
                .run(context -> {
                    assertEquals(6, context.getBean(PaymentCommandProperties.class).partitions());
                    assertEquals(2, context.getBean(PaymentCommandProperties.class).concurrency());
                });
        runner.withPropertyValues("payment.messaging.command.partitions=2", "payment.messaging.command.concurrency=3")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void 토픽_선언은_payment_command를_설정한_파티션_수와_복제_1로_만든다() {
        var topic = new PaymentCommandKafkaConfiguration().paymentCommandTopic(new PaymentCommandProperties(3, 3));

        assertEquals("payment.command", topic.name());
        assertEquals(3, topic.numPartitions());
        assertEquals(1, topic.replicationFactor());
        assertNotNull(TopicBuilder.name("x"));
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(PaymentCommandProperties.class)
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class PropertiesOnly {}
}
