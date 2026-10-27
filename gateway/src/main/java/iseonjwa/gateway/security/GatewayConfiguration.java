package iseonjwa.gateway.security;

import iseonjwa.common.auth.TokenDecoders;
import iseonjwa.common.auth.TokenType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {
    @Bean
    JwtDecoder accessTokenDecoder(@Value("${auth.access-secret}") String secret,
            @Value("${auth.issuer}") String issuer) {
        return TokenDecoders.create(secret, issuer, TokenType.ACCESS);
    }
}
