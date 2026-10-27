package iseonjwa.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class GatewayJwtVerifier {
    private final NimbusJwtDecoder decoder;

    public GatewayJwtVerifier(@Value("${auth.access-secret}") String secret, @Value("${auth.issuer}") String issuer) {
        var key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("서명 키는 32바이트 이상이어야 합니다");
        decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            boolean valid;
            try {
                valid = Long.parseLong(jwt.getSubject()) > 0 && jwt.getId() != null
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                        && jwt.getAudience().contains("iseonjwa-api")
                        && "access".equals(jwt.getClaimAsString("token_type"))
                        && List.of("USER", "ADMIN").contains(jwt.getClaimAsString("role"));
            } catch (RuntimeException exception) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer), contract));
    }

    public Jwt decode(String token) { return decoder.decode(token); }
}
