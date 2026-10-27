package com.iseonjwa.common.response;

/**
 * 업무 규칙 위반을 알리는 예외. 예외 처리기가 {@link ErrorCode}를 실패 응답으로 바꾼다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
