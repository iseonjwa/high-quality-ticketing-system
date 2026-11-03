package com.iseonjwa.payment.refund.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.ConsumerErrorHandlers;
import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.refund.application.DeadLetterInput;
import com.iseonjwa.payment.refund.application.DeadLetterService;
import com.iseonjwa.payment.refund.application.RefundRequestedMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * {@code payment.command.dlq}를 구독해 DLQ로 온 환불 요청을 {@code dead_letters}에 남기고 자동 복구 경로로 되돌린다.
 * 내용 문제로는 예외를 던지지 않는다. 예외는 DB·인프라 오류뿐이고, 그때는 오프셋을 올리지 않고 다시 처리한다
 * ({@link ConsumerErrorHandlers#retryForever()}).
 *
 * <p>Note: 원본 위치는 Spring Kafka가 DLQ 레코드에 붙인 {@code kafka_dlt-original-*} 헤더에서 읽는다. 토픽은 UTF-8 바이트,
 * 파티션은 4바이트 int, 오프셋은 8바이트 long이다. 헤더가 없거나 길이가 맞지 않으면(Kafka-UI로 직접 넣은 메시지 등)
 * DLQ 레코드 자신의 위치를 쓴다.
 */
@Component
public class PaymentCommandDlqListener {
    public static final String TOPIC = "payment.command.dlq";
    public static final String GROUP = DeadLetterService.CONSUMER_GROUP;
    public static final String CONTAINER_FACTORY = "paymentCommandDlqListenerFactory";

    private final RefundRequestedMessageParser parser;
    private final DeadLetterService service;
    private final PaymentMetrics metrics;

    PaymentCommandDlqListener(RefundRequestedMessageParser parser, DeadLetterService service, PaymentMetrics metrics) {
        this.parser = parser;
        this.service = service;
        this.metrics = metrics;
        metrics.trackDlqHandler(TOPIC);
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP, containerFactory = CONTAINER_FACTORY)
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            handle(record);
        } catch (RuntimeException | Error exception) {
            // Note: 예외는 그대로 다시 던진다. 오프셋을 올리지 않고 다시 처리하는 것은 에러 핸들러의 몫이고, 여기서는 건수만 센다
            metrics.dlqHandlerFailure(TOPIC);
            throw exception;
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        var input = input(record);
        RefundRequestedMessage message;
        try {
            message = parser.parse(record.value());
        } catch (NonRetryableMessageException unparsable) {
            // 파싱은 트랜잭션 밖이라 예외를 잡아도 rollback-only 문제가 없다
            service.handleUnparsable(input, unparsable.getMessage());
            return;
        }
        service.handleParsed(input, message.eventId(), message.payload());
    }

    static DeadLetterInput input(ConsumerRecord<String, String> record) {
        var topic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        var partition = header(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        var offset = header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        var raw = record.value() == null ? "" : record.value();
        if (topic != null && partition != null && partition.length == Integer.BYTES && offset != null
                && offset.length == Long.BYTES) {
            return new DeadLetterInput(new String(topic, StandardCharsets.UTF_8), ByteBuffer.wrap(partition).getInt(),
                    ByteBuffer.wrap(offset).getLong(), record.key(), raw);
        }
        return new DeadLetterInput(record.topic(), record.partition(), record.offset(), record.key(), raw);
    }

    private static byte[] header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : header.value();
    }
}
