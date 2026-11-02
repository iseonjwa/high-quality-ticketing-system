package com.iseonjwa.mockpg.web;

/**
 * Mock PG 오류 응답 본문. 어댑터는 상태 코드와 본문의 code로 결과를 분류하므로 Boot 기본 오류 본문(code 없음)에
 * 맡기지 않고 서버가 직접 만든다(mock-pg.md 11절). code 없는 404는 어댑터가 CLIENT_ERROR로 본다.
 */
public record ErrorResponse(String code) {
    public static final ErrorResponse NOT_FOUND = new ErrorResponse("NOT_FOUND");
}
