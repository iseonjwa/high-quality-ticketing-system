package com.iseonjwa.payment.refund.application;

/**
 * DLQ 메시지에서 DLQ 처리기가 필요로 하는 값. {@code source*}는 DLQ 레코드가 아니라 <b>원본</b> 메시지의 위치다.
 */
public record DeadLetterInput(String sourceTopic, int sourcePartition, long sourceOffset, String messageKey,
                              String payloadRaw) {}
