package com.iseonjwa.reservation.seathold.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;

import java.util.UUID;
import java.util.regex.Pattern;

// 선점 접수 ID(= Idempotency-Key)를 읽는다. 선점 API와 결과 조회 API가 같은 형식 규칙을 쓴다
final class RequestIds {
    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RequestIds() {}

    static UUID parse(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return UUID.fromString(value);
    }
}
