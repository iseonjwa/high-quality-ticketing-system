package com.iseonjwa.mockpg.web;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 응답 유실(DROP_RESPONSE)을 만든다. 처리를 끝낸 요청의 응답을 한 바이트도 보내지 않고 연결을 끊어
 * 어댑터가 응답 시간 초과(TIMEOUT)가 아니라 연결 끊김(CONNECTION_LOST)으로 분류하게 한다(mock-pg.md 6절).
 *
 * <p>Note: 서블릿 필터가 아니라 Valve인 이유는 연결을 즉시 닫는 {@code CLOSE_NOW}가 Tomcat 내부 응답
 * 객체에만 있기 때문이다. 필터가 받는 {@code ResponseFacade}는 내부 객체를 꺼낼 공개 메서드가 없다.
 */
class DropResponseValve extends ValveBase {
    private static final Logger log = LoggerFactory.getLogger(DropResponseValve.class);

    DropResponseValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        getNext().invoke(request, response);
        if (!ResponseDrop.isRequested(request)) return;
        if (response.isCommitted()) {
            // 상태 줄이 이미 나갔으면 0바이트 유실이 아니다. 컨트롤러가 본문을 쓰지 않았는지 확인해야 한다
            log.warn("응답이 이미 커밋돼 0바이트 유실을 만들 수 없다 uri={}", request.getRequestURI());
        }
        // Note: CLOSE_NOW 뒤에도 Tomcat은 응답을 마무리하며 상태 줄과 헤더를 소켓 쓰기 버퍼에 쓴다. 다만 그 버퍼를
        // flush하지 않고 연결을 닫으므로 한 바이트도 나가지 않는다(Tomcat 11.0.27 소스로 확인, mock-pg.md 6절)
        response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
    }
}
