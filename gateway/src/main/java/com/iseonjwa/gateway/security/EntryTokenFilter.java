package com.iseonjwa.gateway.security;

import com.iseonjwa.common.auth.EntryTokenClaims;
import com.iseonjwa.common.auth.EntryTokenDecoder;
import com.iseonjwa.common.identity.IdentityHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 좌석 조회·선점 앞에서 입장 토큰({@code X-Entry-Token})을 확인해 대기열을 거치지 않은 요청을 막는다(ISE-58, E5·E14).
 * 서명·{@code typ}·{@code aud}·{@code exp}는 {@link EntryTokenDecoder}가, 사용자·회차 일치는 여기서 본다.
 * Redis나 서비스를 부르지 않고 토큰만으로 판단한다(SA 9-5).
 */
@Component
public class EntryTokenFilter implements GlobalFilter, Ordered {
    public static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";

    private final EntryTokenDecoder decoder;
    private final GatewayErrorWriter errors;

    public EntryTokenFilter(EntryTokenDecoder decoder, GatewayErrorWriter errors) {
        this.decoder = decoder;
        this.errors = errors;
    }

    // Note: 인증 필터(-100) 뒤에 돈다. 그 필터가 클라이언트가 보낸 사용자 헤더를 지우고 검증한 Access 토큰의 값만 넣으므로,
    // 여기서 읽는 X-User-Id는 로그인 사용자다. 입장 토큰 sub를 이 값과 비교한다(ADR 0015).
    @Override
    public int getOrder() {
        return -90;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var pathScheduleId = EntryTokenPolicy.scheduleIdOf(request.getMethod(),
                request.getPath().pathWithinApplication());
        if (pathScheduleId.isEmpty()) {
            return chain.filter(exchange);
        }
        if (!isValid(request.getHeaders().getFirst(ENTRY_TOKEN_HEADER),
                request.getHeaders().getFirst(IdentityHeaders.USER_ID), pathScheduleId.get())) {
            // 이유는 응답에 담지 않는다(계약 ① Gateway의 동작)
            return errors.write(exchange, 403, "ENTRY_TOKEN_INVALID", "입장 토큰이 유효하지 않습니다");
        }
        return chain.filter(exchange);
    }

    private boolean isValid(String token, String userId, String pathScheduleId) {
        if (token == null || userId == null) {
            return false;
        }
        try {
            var jwt = decoder.decode(token);
            Object scheduleId = jwt.getClaim(EntryTokenClaims.SCHEDULE_ID);
            return userId.equals(jwt.getSubject())
                    && scheduleId instanceof Long id && id == Long.parseLong(pathScheduleId);
        } catch (JwtException | IllegalArgumentException exception) {
            // 위조·만료·형식 오류·다른 종류 토큰, 그리고 숫자가 아닌 경로 회차 ID
            return false;
        }
    }
}
