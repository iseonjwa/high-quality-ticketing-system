package com.iseonjwa.mockpg.web;

import jakarta.servlet.ServletRequest;

/**
 * 장애 주입 DROP_RESPONSE의 요청 표시. 컨트롤러가 결과를 저장한 뒤 표시하면 {@link DropResponseValve}가
 * 서블릿 처리가 끝난 뒤 연결을 닫는다.
 */
public final class ResponseDrop {
    private static final String ATTRIBUTE = ResponseDrop.class.getName();

    private ResponseDrop() {
    }

    public static void request(ServletRequest request) {
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
    }

    static boolean isRequested(ServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
    }
}
