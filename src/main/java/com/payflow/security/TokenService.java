package com.payflow.security;

import com.payflow.config.PayflowProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Simplified OAuth 2.0 client-credentials grant: verifies client_id/secret (BCrypt) and issues a JWT.
 */
@Service
public class TokenService {

    /** BCrypt hash of a random string: compared against when the client id is unknown (constant-ish timing). */
    private static final String DUMMY_HASH = "$2b$10$OTD3ubnEdr91jkmfBUtcoOUCHQQ3WG3SVUERTTF4GnXT1Db3OxwUq";

    private final ApiClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final PayflowProperties props;
    private final Clock clock;

    public TokenService(ApiClientRepository clients, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                        PayflowProperties props, Clock clock) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.clock = clock;
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {
    }

    @Transactional(readOnly = true)
    public IssuedToken issue(String clientId, String clientSecret) {
        var client = clients.findByClientId(clientId).orElse(null);
        // Always run BCrypt so "unknown client" and "wrong secret" take similar time (no client enumeration).
        boolean secretOk = passwordEncoder.matches(clientSecret,
                client != null ? client.getClientSecretHash() : DUMMY_HASH);
        if (client == null || !secretOk || !client.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid client credentials");
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.security().tokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.security().jwtIssuer())
                .subject(client.getClientId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", List.copyOf(client.roleSet()));
        if (client.getMerchantId() != null) {
            claims.claim("merchant_id", client.getMerchantId().toString());
        }
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new IssuedToken(token, props.security().tokenTtl().toSeconds());
    }
}
