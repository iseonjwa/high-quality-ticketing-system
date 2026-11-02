package com.iseonjwa.mockpg.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 형식이 틀린 요청 본문(JSON 오류, 필수 값 누락, 0 이하 금액)에 Boot 기본 400 대신 프로토콜의 오류 본문으로 답한다(mock-pg.md 11절). 모든 컨트롤러(장애 주입 설정 포함)에 적용된다.
 */
@RestControllerAdvice
class InvalidRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(InvalidRequestHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> invalidRequest(HttpMessageNotReadableException exception) {
        log.info("잘못된 요청 본문: {}", exception.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.INVALID_REQUEST);
    }
}
