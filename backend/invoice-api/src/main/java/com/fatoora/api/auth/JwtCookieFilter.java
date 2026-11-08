package com.fatoora.api.auth;

import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
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
    private final AppUserRepository users;

    public JwtCookieFilter(JwtService jwt, AppUserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.COOKIE.equals(c.getName())) {
                    // The account is read on every request, so disabling a user or changing their role
                    // takes effect at once, not when their session cookie expires.
                    jwt.parse(c.getValue())
                            .flatMap(token -> users.findById(token.userId()))
                            .filter(AppUser::isEnabled)
                            .map(u -> new CurrentUser(u.getId(), u.getOrganizationId(), u.getEmail(), u.getRole()))
                            .ifPresent(user -> {
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
