package com.fatoora.api.config;

import com.fatoora.api.auth.JwtCookieFilter;
import com.fatoora.api.auth.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.function.Supplier;

/**
 * Stateless API: identity comes from an httpOnly JWT cookie; CSRF uses the SPA double-submit
 * pattern (XSRF-TOKEN cookie echoed back in the X-XSRF-TOKEN header).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, com.fatoora.api.user.AppUserRepository users) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.spa())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        // The container's error page for a refused request runs without the session cookie being
                        // read again; let it through so a refusal stays a 403 instead of turning into a 401.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/health", "/api/v1/organization/logo").permitAll()
                        // Invoice PDF links in customer reminders, guarded by an HMAC token in the path.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/invoices/*/*").permitAll()
                        // Signed in; a branch login is further held to its own areas (see BRANCH_ALLOWED).
                        .requestMatchers("/api/v1/**").access(SecurityConfig::branchScope)
                        .anyRequest().denyAll())
                .addFilterBefore(new JwtCookieFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * What a BRANCH login may call: its invoices (with their payments), quotations, agreements, the shared
     * customer and product lists for the editors, its own cash-book entries and its own dashboard. Every
     * record those return is filtered to what the login created; everything else is refused.
     */
    private static final List<RequestMatcher> BRANCH_ALLOWED = List.of(
            m(null, "/api/v1/auth/**"),
            m(HttpMethod.GET, "/api/v1/organization"),
            m(HttpMethod.GET, "/api/v1/dashboard"),
            m(null, "/api/v1/invoices"), m(null, "/api/v1/invoices/**"),
            m(null, "/api/v1/payments/**"),
            m(null, "/api/v1/quotations"), m(null, "/api/v1/quotations/**"),
            m(null, "/api/v1/agreements"), m(null, "/api/v1/agreements/**"),
            m(HttpMethod.GET, "/api/v1/agreement-template"),
            m(HttpMethod.GET, "/api/v1/customers"), m(HttpMethod.GET, "/api/v1/customers/**"),
            m(HttpMethod.POST, "/api/v1/customers"),
            m(HttpMethod.GET, "/api/v1/products"), m(HttpMethod.GET, "/api/v1/products/**"),
            m(HttpMethod.GET, "/api/v1/accounts/month"), m(HttpMethod.GET, "/api/v1/accounts/month.xlsx"),
            m(HttpMethod.GET, "/api/v1/accounts/month/pdf"),
            m(null, "/api/v1/accounts/income"), m(null, "/api/v1/accounts/income/**"),
            m(null, "/api/v1/accounts/expenses"), m(null, "/api/v1/accounts/expenses/**"));

    private static RequestMatcher m(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    private static AuthorizationDecision branchScope(Supplier<? extends Authentication> auth, RequestAuthorizationContext ctx) {
        Authentication a = auth.get();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return new AuthorizationDecision(false);
        boolean branch = a.getAuthorities().stream().anyMatch(g -> "ROLE_BRANCH".equals(g.getAuthority()));
        return new AuthorizationDecision(!branch || BRANCH_ALLOWED.stream().anyMatch(r -> r.matches(ctx.getRequest())));
    }

    /** Each role includes the permissions of the ones below it. */
    @Bean
    static org.springframework.security.access.hierarchicalroles.RoleHierarchy roleHierarchy() {
        return org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl.fromHierarchy("""
                ROLE_OPERATOR > ROLE_ADMIN
                ROLE_ADMIN > ROLE_ACCOUNTANT
                ROLE_ACCOUNTANT > ROLE_VIEWER
                """);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
