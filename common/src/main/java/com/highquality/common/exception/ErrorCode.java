package com.highquality.common.exception;

/**
 * 에러 코드 계약. 공통 코드는 {@link CommonErrorCode}, 업무 에러 코드는 각 서비스 enum이 구현한다(SA 11-2).
 * Gateway(WebFlux)도 쓰므로 Spring 타입(HttpStatus)에 의존하지 않고 상태 코드를 int로 둔다.
 */
public interface ErrorCode {

    int status();

    String code();

    String message();
}
