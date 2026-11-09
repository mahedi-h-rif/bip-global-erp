package com.fatoora.api.pdf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request print choices (video §1.2: two print templates, with and without the logo — for pre-printed letterhead
 * paper). Any PDF URL accepts {@code ?logo=false}; the PDF builders ask {@link #showLogo()}.
 */
@Component
public class PrintOptions extends OncePerRequestFilter {

    private static final ThreadLocal<Boolean> HIDE_LOGO = ThreadLocal.withInitial(() -> false);

    public static boolean showLogo() {
        return !HIDE_LOGO.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        HIDE_LOGO.set("false".equalsIgnoreCase(req.getParameter("logo")));
        try {
            chain.doFilter(req, res);
        } finally {
            HIDE_LOGO.remove();
        }
    }
}
