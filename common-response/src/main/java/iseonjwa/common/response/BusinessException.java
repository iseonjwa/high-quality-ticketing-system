package iseonjwa.common.response;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).message());
        if (errorCode.status() < 400 || errorCode.status() > 599) {
            throw new IllegalArgumentException("업무 예외는 4xx 또는 5xx 상태가 필요합니다");
        }
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
