package com.fatoora.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The API rate limits in force: what the company admin set under Settings → Company, or the server default
 * (env) when nothing is set. Read from the database at most every 30 seconds, and at once after a save.
 */
@Component
public class RateLimitSettings {

    public record Limits(int perMinute, int loginPerMinute) {}

    private static final long TTL_MS = 30_000;

    private final JdbcTemplate jdbc;
    private final Limits defaults;
    private volatile Limits cached;
    private volatile long loadedAt;

    public RateLimitSettings(JdbcTemplate jdbc,
                             @Value("${fatoora.rate-limit.requests-per-minute:600}") int perMinute,
                             @Value("${fatoora.rate-limit.login-per-minute:10}") int loginPerMinute) {
        this.jdbc = jdbc;
        this.defaults = new Limits(perMinute, loginPerMinute);
    }

    public Limits defaults() {
        return defaults;
    }

    public Limits current() {
        long now = System.currentTimeMillis();
        Limits c = cached;
        if (c != null && now - loadedAt < TTL_MS) return c;
        c = load();
        cached = c;
        loadedAt = now;
        return c;
    }

    /** After the admin saves new limits. */
    public void invalidate() {
        cached = null;
    }

    private Limits load() {
        try {
            // One company per deployment: the first organization.
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT api_rate_limit_per_minute, login_rate_limit_per_minute FROM organization ORDER BY id LIMIT 1");
            if (rows.isEmpty()) return defaults;
            Object api = rows.getFirst().get("api_rate_limit_per_minute");
            Object login = rows.getFirst().get("login_rate_limit_per_minute");
            return new Limits(api == null ? defaults.perMinute() : ((Number) api).intValue(),
                    login == null ? defaults.loginPerMinute() : ((Number) login).intValue());
        } catch (RuntimeException e) {
            return defaults; // the database is briefly unavailable: keep protecting with the defaults
        }
    }
}
