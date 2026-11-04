package com.iseonjwa.payment.refund.application;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;

/**
 * {@code RefundRequested} 메시지 원문을 읽는 포트. 소비자·DLQ 처리기와 Ops {@code dead-letter resolve --replay}가 같은
 * 파서를 쓰게 하려고 구현({@code refund.presentation.messaging})을 application에서 가린다.
 */
public interface RefundRequestedParser {
    /** @throws NonRetryableMessageException 원문이 봉투 규칙에 맞지 않을 때 */
    RefundRequestedMessage parse(String value);
}
