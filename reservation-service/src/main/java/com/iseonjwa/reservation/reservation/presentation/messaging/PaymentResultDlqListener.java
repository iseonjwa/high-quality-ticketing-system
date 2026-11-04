package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.reservation.application.PaymentResult;
import com.iseonjwa.reservation.reservation.application.PaymentResultDlqService;
import com.iseonjwa.reservation.reservation.domain.entity.DeadLetterMessage;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * {@code payment.result.dlq}를 구독해 DLQ로 온 결제 결과를 {@link PaymentResultDlqService}에 넘긴다(ISE-74).
 *
 * <p>Note: 원본 위치는 Spring Kafka가 DLQ 레코드에 붙인 {@code kafka_dlt-original-*} 헤더에서 읽는다. 토픽은 UTF-8 바이트,
 * 파티션은 4바이트 int, 오프셋은 8바이트 long이다. 헤더가 없거나 길이가 맞지 않으면(Kafka-UI로 직접 넣은 메시지 등)
 * DLQ 레코드 자신의 위치를 쓴다. 결제 서비스 PaymentCommandDlqListener와 같은 방식이다.
 */
@Component
@RequiredArgsConstructor
public class PaymentResultDlqListener {
    public static final String TOPIC = "payment.result.dlq";
    public static final String CONTAINER_FACTORY = "paymentResultDlqListenerFactory";

    private final PaymentResultMessageParser parser;
    private final PaymentResultDlqService service;

    @KafkaListener(topics = TOPIC, groupId = PaymentResultDlqService.CONSUMER_GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(ConsumerRecord<String, String> record) {
        var message = message(record);
        PaymentResult result;
        try {
            result = parser.parse(record.value());
        } catch (NonRetryableMessageException unparsable) {
            service.recordUnparsable(message, unparsable.getMessage());
            return;
        }
        service.replay(message, result);
    }

    static DeadLetterMessage message(ConsumerRecord<String, String> record) {
        var topic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        var partition = header(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        var offset = header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        if (topic != null && partition != null && partition.length == Integer.BYTES && offset != null
                && offset.length == Long.BYTES) {
            return DeadLetterMessage.of(new String(topic, StandardCharsets.UTF_8), ByteBuffer.wrap(partition).getInt(),
                    ByteBuffer.wrap(offset).getLong(), record.key(), record.value());
        }
        return DeadLetterMessage.of(record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }

    private static byte[] header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : header.value();
    }
}
