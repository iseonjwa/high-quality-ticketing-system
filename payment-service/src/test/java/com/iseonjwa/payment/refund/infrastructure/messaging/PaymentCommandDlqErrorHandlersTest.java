package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.payment.refund.application.NonRetryableMessageException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 브로커 없이 DLQ 리스너 전용 오류 처리기를 단위로 확인한다. 컨테이너가 record 리스너 오류를 넘기는 경로(handleRemaining)를 부른다.
class PaymentCommandDlqErrorHandlersTest {
    private static final int RETRIES = 100;

    @Test
    void 운영_백오프는_1_2_4초로_늘다가_60초에서_멈추고_끝나지_않는다() {
        var execution = PaymentCommandDlqErrorHandlers.productionBackOff().start();

        var expected = new long[] {1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000};
        for (var interval : expected) {
            assertEquals(interval, execution.nextBackOff());
        }
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            var next = execution.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, next, "시도 " + attempt + "번째에 STOP");
            assertEquals(60_000, next);
        }
    }

    @Test
    void 운영_값은_1초_시작_2배_최대_60초다() {
        assertEquals(1_000, PaymentCommandDlqErrorHandlers.INITIAL_INTERVAL_MILLIS);
        assertEquals(2.0, PaymentCommandDlqErrorHandlers.MULTIPLIER);
        assertEquals(60_000, PaymentCommandDlqErrorHandlers.MAX_INTERVAL_MILLIS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 일시_오류는_몇_번을_반복해도_건너뛰지_않고_같은_레코드를_다시_읽는다() {
        var handler = PaymentCommandDlqErrorHandlers.create(PaymentCommandDlqErrorHandlers.unlimitedBackOff(1, 1.0, 1));
        var consumer = mock(Consumer.class);
        var record = new ConsumerRecord<Object, Object>("payment.command.dlq", 2, 42L, "k", "v");
        var failure = new ListenerExecutionFailedException("failed", new DataAccessResourceFailureException("DB 연결 실패"));

        for (int attempt = 0; attempt < RETRIES; attempt++) {
            assertRetryPending(() -> handler.handleRemaining(failure, List.of(record), consumer, mock(MessageListenerContainer.class)));
        }

        verify(consumer, times(RETRIES)).seek(new TopicPartition("payment.command.dlq", 2), 42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 어떤_예외도_재시도_대상이다() {
        var handler = PaymentCommandDlqErrorHandlers.create(PaymentCommandDlqErrorHandlers.unlimitedBackOff(1, 1.0, 1));
        var consumer = mock(Consumer.class);
        var record = new ConsumerRecord<Object, Object>("payment.command.dlq", 0, 1L, "k", "v");

        // DefaultErrorHandler는 기본으로 이 예외들을 재시도 없이 복구(건너뛰기)한다. 여기서는 그렇게 하지 않아야 한다
        for (Exception cause : new Exception[] {new NonRetryableMessageException("내용 오류"), new ClassCastException("변환"),
                new IllegalArgumentException("인자"), new org.springframework.kafka.support.serializer.DeserializationException(
                        "역직렬화", new byte[0], false, new RuntimeException())}) {
            assertRetryPending(() -> handler.handleRemaining(new ListenerExecutionFailedException("failed", cause),
                    List.of(record), consumer, mock(MessageListenerContainer.class)));
        }
    }

    // 아직 복구되지 않아 같은 레코드를 다시 처리해야 할 때 던지는 예외다. 이 예외 클래스는 패키지 비공개라 이름으로 확인한다
    private static void assertRetryPending(org.junit.jupiter.api.function.Executable handling) {
        var thrown = assertThrows(RuntimeException.class, handling);
        assertEquals("RecordInRetryException", thrown.getClass().getSimpleName());
    }
}
