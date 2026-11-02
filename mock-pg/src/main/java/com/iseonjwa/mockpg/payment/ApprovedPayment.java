package com.iseonjwa.mockpg.payment;

import java.math.BigDecimal;
import java.time.Instant;

record ApprovedPayment(String orderId, String pgTxId, BigDecimal amount, String currency, Instant approvedAt) {
}
