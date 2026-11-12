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

import java.util.Optional;

/**
 * 좌석 조회·선점 앞에서 입장 토큰({@code X-Entry-Token})을 확인해 대기열을 거치지 않은 요청을 막는다(ISE-58, E5·E14).
 * 서명·{@code typ}·{@code aud}·{@code exp}는 {@link EntryTokenDecoder}가, 사용자·회차 일치는 여기서 본다.
 * Redis나 서비스를 부르지 않고 토큰만으로 판단한다(SA 9-5).
 * 통과한 토큰의 {@code jti}는 {@link #ENTRY_TOKEN_ID_HEADER}로 넘겨 선점 API가 소모 여부를 확인하게 한다(ADR 0053).
 */
@Component
public class EntryTokenFilter implements GlobalFilter, Ordered {
    public static final String ENTRY_TOKEN_HEADER = "X-Entry-Token";
    public static final String ENTRY_TOKEN_ID_HEADER = "X-Entry-Token-Id";

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
            return chain.filter(withoutClaimedTokenId(exchange));
        }
        var tokenId = verifiedTokenId(request.getHeaders().getFirst(ENTRY_TOKEN_HEADER),
                request.getHeaders().getFirst(IdentityHeaders.USER_ID), pathScheduleId.get());
        if (tokenId.isEmpty()) {
            // 이유는 응답에 담지 않는다(계약 ① Gateway의 동작)
            return errors.write(exchange, 403, "ENTRY_TOKEN_INVALID", "입장 토큰이 유효하지 않습니다");
        }
        return chain.filter(withTokenId(exchange, tokenId.get()));
    }

    // 검증을 통과하면 토큰의 jti를 돌려준다. EntryTokenDecoder가 jti 없는 토큰을 거절하므로 통과한 토큰에는 늘 있다
    private Optional<String> verifiedTokenId(String token, String userId, String pathScheduleId) {
        if (token == null || userId == null) {
            return Optional.empty();
        }
        try {
            var jwt = decoder.decode(token);
            Object scheduleId = jwt.getClaim(EntryTokenClaims.SCHEDULE_ID);
            var matches = userId.equals(jwt.getSubject())
                    && scheduleId instanceof Long id && id == Long.parseLong(pathScheduleId);
            return matches ? Optional.ofNullable(jwt.getId()) : Optional.empty();
        } catch (JwtException | IllegalArgumentException exception) {
            // 위조·만료·형식 오류·다른 종류 토큰, 그리고 숫자가 아닌 경로 회차 ID
            return Optional.empty();
        }
    }

    // Note: 클라이언트가 보낸 X-Entry-Token-Id는 어느 경로에서든 지운다. 남겨 두면 입장 토큰이 필요 없는 경로나
    // 나중에 이 헤더를 읽게 될 서비스에서 남의 jti를 사칭할 수 있다(인증 필터가 X-User-Id를 지우는 것과 같은 이유).
    // 대부분의 요청에는 이 헤더가 없으므로, 있을 때만 요청을 바꿔 피크 경로에 할당을 더하지 않는다.
    private ServerWebExchange withoutClaimedTokenId(ServerWebExchange exchange) {
        if (!exchange.getRequest().getHeaders().containsHeader(ENTRY_TOKEN_ID_HEADER)) {
            return exchange;
        }
        return exchange.mutate()
                .request(request -> request.headers(headers -> headers.remove(ENTRY_TOKEN_ID_HEADER)))
                .build();
    }

    // set은 같은 이름의 기존 값을 모두 바꾸므로 클라이언트가 보낸 값은 남지 않는다
    private ServerWebExchange withTokenId(ServerWebExchange exchange, String tokenId) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(ENTRY_TOKEN_ID_HEADER, tokenId)))
                .build();
    }
}
