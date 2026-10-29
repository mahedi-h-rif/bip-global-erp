package com.fatoora.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Authenticates requests carrying a valid session cookie. */
public class JwtCookieFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtCookieFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.COOKIE.equals(c.getName())) {
                    jwt.parse(c.getValue()).ifPresent(user -> {
                        var auth = new UsernamePasswordAuthenticationToken(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                }
            }
        }
        chain.doFilter(req, res);
    }
}
