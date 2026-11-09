package com.fatoora.api.auth;

import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.user.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    /**
     * Session cookie name. Browsers share cookies between ports of one host, so two instances on the same server
     * (UAT :8088, production :8089) need different names (FATOORA_COOKIE_NAME) or each login logs the other out.
     */
    public static final String COOKIE = java.util.Optional.ofNullable(System.getenv("FATOORA_COOKIE_NAME"))
            .filter(s -> s.matches("[A-Za-z0-9_]+")).orElse("fatoora_session");

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(FatooraProperties props) {
        String secret = props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("FATOORA_JWT_SECRET must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().ttl();
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("org", user.getOrganizationId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<CurrentUser> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new CurrentUser(
                    c.get("uid", Number.class).longValue(),
                    c.get("org", Number.class).longValue(),
                    c.getSubject(),
                    AppUser.Role.valueOf(c.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
