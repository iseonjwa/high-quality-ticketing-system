package com.iseonjwa.payment.reconciliation.domain;

import java.util.Map;

/**
 * 규칙이 찾은 불일치 하나.
 *
 * @param key    같은 불일치를 이어 보는 키
 * @param detail 양쪽 상태·금액·사용자·시각. JSON으로 저장한다
 */
public record Finding(MismatchKey key, Map<String, Object> detail) {}
