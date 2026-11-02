package com.iseonjwa.mockpg.web;

/**
 * Mock PG 오류 응답 본문. 어댑터는 상태 코드와 본문의 code로 결과를 분류하므로 Boot 기본 오류 본문(code 없음)에
 * 맡기지 않고 서버가 직접 만든다(mock-pg.md 11절). code 없는 404는 어댑터가 CLIENT_ERROR로 본다.
 */
public record ErrorResponse(String code) {
    public static final ErrorResponse NOT_FOUND = new ErrorResponse("NOT_FOUND");
    // 망취소된 주문의 승인. 어댑터는 이 코드만 거절(NOT_SENT)로 본다
    public static final ErrorResponse ORDER_CANCELED = new ErrorResponse("ORDER_CANCELED");
    // 같은 주문의 승인이 처리 중이다. 어댑터는 처리 중(Unknown)으로 본다
    public static final ErrorResponse REQUEST_IN_PROGRESS = new ErrorResponse("REQUEST_IN_PROGRESS");
    // 장애 주입 SERVER_ERROR. 어댑터는 PG 오류(Unknown)로 본다
    public static final ErrorResponse SERVER_ERROR = new ErrorResponse("SERVER_ERROR");
    public static final ErrorResponse INVALID_REQUEST = new ErrorResponse("INVALID_REQUEST");
}
