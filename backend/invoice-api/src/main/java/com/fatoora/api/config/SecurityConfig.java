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

/**
 * Stateless API: identity comes from an httpOnly JWT cookie; CSRF uses the SPA double-submit
 * pattern (XSRF-TOKEN cookie echoed back in the X-XSRF-TOKEN header).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.spa())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/health", "/api/v1/organization/logo").permitAll()
                        // Invoice PDF links in customer reminders, guarded by an HMAC token in the path.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/invoices/*/*").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new JwtCookieFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
