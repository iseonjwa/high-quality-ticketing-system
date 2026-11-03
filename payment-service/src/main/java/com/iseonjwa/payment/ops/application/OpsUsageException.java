package com.iseonjwa.payment.ops.application;

/** 인자를 해석할 수 없다(없는 명령, 모르는 옵션, 형식이 틀린 값). 종료 코드 1이다. */
public class OpsUsageException extends RuntimeException {
    public OpsUsageException(String message) {
        super(message);
    }
}
