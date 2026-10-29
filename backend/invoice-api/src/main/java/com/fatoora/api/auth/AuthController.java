package com.fatoora.api.auth;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record Me(Long id, String email, String fullName, String role, Long organizationId) {}

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginAttemptLimiter limiter;
    private final AuditService audit;
    private final boolean secureCookie;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt, LoginAttemptLimiter limiter,
                          AuditService audit, FatooraProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.limiter = limiter;
        this.audit = audit;
        this.secureCookie = props.jwt().cookieSecure();
    }

    /** Touching the token makes Spring Security send the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    @Transactional
    ResponseEntity<Me> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
        String key = body.email().toLowerCase() + "|" + req.getRemoteAddr();
        if (limiter.isLocked(key))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked", "Too many failed attempts. Try again in 15 minutes.");

        AppUser user = users.findByEmailIgnoreCase(body.email())
                .filter(AppUser::isEnabled)
                .filter(u -> encoder.matches(body.password(), u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            limiter.failed(key);
            audit.record(null, "LOGIN_FAILED", "user", null, body.email());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Email or password is incorrect");
        }
        limiter.succeeded(key);
        user.setLastLoginAt(Instant.now());
        audit.record(user.getOrganizationId(), "LOGIN", "user", user.getId(), null);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(jwt.issue(user), jwt.ttl()).toString())
                .body(toMe(user));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    Me me() {
        CurrentUser cu = CurrentUser.get();
        return users.findById(cu.userId()).map(AuthController::toMe)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown_user", "User no longer exists"));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtService.COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static Me toMe(AppUser u) {
        return new Me(u.getId(), u.getEmail(), u.getFullName(), u.getRole().name(), u.getOrganizationId());
    }
}
