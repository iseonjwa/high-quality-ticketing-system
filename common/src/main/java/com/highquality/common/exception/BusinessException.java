package com.highquality.common.exception;

import lombok.Getter;

/**
 * 업무 규칙 위반을 알리는 예외. 서비스는 이 예외를 던지고, 응답 변환은 예외 처리기가 맡는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
