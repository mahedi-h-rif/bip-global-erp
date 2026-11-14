package com.fatoora.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Per-visitor request limits, checked before authentication so floods cost almost nothing:
 * every API call counts towards the per-minute limit, and sign-in attempts also towards the much smaller sign-in
 * limit. The company admin sets both under Settings → Company ({@link RateLimitSettings}); 0 means no limit.
 * Over the limit → 429 with Retry-After.
 * Fixed one-minute windows, in memory (one API instance per database).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {}

    private final Supplier<RateLimitSettings.Limits> limits;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    @Autowired
    public RateLimitFilter(RateLimitSettings settings) {
        this.limits = settings::current;
    }

    /** Fixed limits (tests). */
    RateLimitFilter(int perMinute, int loginPerMinute) {
        RateLimitSettings.Limits fixed = new RateLimitSettings.Limits(perMinute, loginPerMinute);
        this.limits = () -> fixed;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        RateLimitSettings.Limits l = limits.get();
        long minute = System.currentTimeMillis() / 60_000;
        sweep(minute);
        String ip = ClientIp.of(req);
        boolean login = "POST".equals(req.getMethod()) && req.getRequestURI().equals("/api/v1/auth/login");
        if (over("all|" + ip, minute, l.perMinute()) || (login && over("login|" + ip, minute, l.loginPerMinute()))) {
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
        if (limit <= 0) return false; // 0 = no limit
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
