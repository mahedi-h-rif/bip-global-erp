package com.fatoora.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-visitor request limits, checked before authentication so floods cost almost nothing:
 * every API call counts towards {@code fatoora.rate-limit.requests-per-minute}, and sign-in attempts also
 * towards the much smaller {@code login-per-minute}. Over the limit → 429 with Retry-After.
 * Fixed one-minute windows, in memory (one API instance per database).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {}

    private final int perMinute;
    private final int loginPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    public RateLimitFilter(@Value("${fatoora.rate-limit.requests-per-minute:600}") int perMinute,
                           @Value("${fatoora.rate-limit.login-per-minute:10}") int loginPerMinute) {
        this.perMinute = perMinute;
        this.loginPerMinute = loginPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return perMinute <= 0 || !path.startsWith("/api/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        sweep(minute);
        String ip = ClientIp.of(req);
        boolean login = "POST".equals(req.getMethod()) && req.getRequestURI().equals("/api/v1/auth/login");
        if (over("all|" + ip, minute, perMinute) || (login && over("login|" + ip, minute, loginPerMinute))) {
            long retry = 60 - (System.currentTimeMillis() / 1000) % 60;
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(retry));
            res.setContentType("application/json");
            res.getWriter().write("{\"code\":\"rate_limited\",\"message\":\"Too many requests. Please wait "
                    + retry + " seconds and try again.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean over(String key, long minute, int limit) {
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        return w.count().incrementAndGet() > limit;
    }

    /** Drops finished windows once a minute so memory stays small. */
    private void sweep(long minute) {
        if (lastSweep == minute) return;
        lastSweep = minute;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }
}
