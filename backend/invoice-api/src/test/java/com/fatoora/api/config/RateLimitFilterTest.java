package com.fatoora.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static MockHttpServletRequest req(String method, String path, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        r.setRemoteAddr("172.18.0.5"); // the portal container
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    private static int call(RateLimitFilter f, MockHttpServletRequest r) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(r, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void realClientIpIsTheRightMostPublicHop() {
        assertThat(ClientIp.of(req("GET", "/api/v1/x", "203.0.113.7"))).isEqualTo("203.0.113.7");
        // Spoofed entry on the left, our proxies' entries on the right.
        assertThat(ClientIp.of(req("GET", "/api/v1/x", "1.2.3.4, 203.0.113.7, 172.18.0.9"))).isEqualTo("203.0.113.7");
        assertThat(ClientIp.of(req("GET", "/api/v1/x", null))).isEqualTo("172.18.0.5");
        assertThat(ClientIp.of(req("GET", "/api/v1/x", "not-an-ip, evil.example"))).isEqualTo("172.18.0.5");
    }

    @Test
    void limitsEachVisitorSeparately() throws Exception {
        RateLimitFilter f = new RateLimitFilter(3, 2);
        for (int i = 0; i < 3; i++) assertThat(call(f, req("GET", "/api/v1/invoices", "203.0.113.7"))).isEqualTo(200);
        assertThat(call(f, req("GET", "/api/v1/invoices", "203.0.113.7"))).isEqualTo(429);
        assertThat(call(f, req("GET", "/api/v1/invoices", "198.51.100.2"))).isEqualTo(200);
        assertThat(call(f, req("GET", "/api/v1/health", "203.0.113.7"))).isEqualTo(200); // health is never limited
    }

    @Test
    void signInHasItsOwnSmallerLimit() throws Exception {
        RateLimitFilter f = new RateLimitFilter(100, 2);
        for (int i = 0; i < 2; i++) assertThat(call(f, req("POST", "/api/v1/auth/login", "203.0.113.7"))).isEqualTo(200);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req("POST", "/api/v1/auth/login", "203.0.113.7"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotBlank();
        assertThat(call(f, req("GET", "/api/v1/invoices", "203.0.113.7"))).isEqualTo(200);
    }
}
