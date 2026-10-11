package com.janemichael.jmrecruitingcrm.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.time.Duration;
import java.util.Map;

/**
 * Session-based sign-in for the founders. The React app posts the login form to
 * /api/auth/login, receives a session cookie, and sends the XSRF-TOKEN cookie back
 * as a header on every change.
 */
@Configuration
public class SecurityConfig {

    /**
     * Only this app's own files may load. Blocks injected scripts, framing and form posts
     * to other sites. Fonts are self-hosted, so no outside origins are needed.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "font-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "manifest-src 'self'",
            "worker-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'");

    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            LoginThrottle loginThrottle,
            AppUserRepository userRepository
    ) throws Exception {
        http
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new SecurityFilters.LoginThrottleFilter(loginThrottle),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new SecurityFilters.ActiveUserFilter(userRepository), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/csrf", "/api/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        // The React app owns the /login page; Spring only handles the POST.
                        .loginPage("/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            loginThrottle.recordSuccess(request.getParameter("email"));
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                        .failureHandler((request, response, exception) -> {
                            loginThrottle.recordFailure(request.getParameter("email"), request.getRemoteAddr());
                            SecurityFilters.writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                    "Unauthorized", "Invalid email or password");
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(frame -> frame.deny())
                        // Sent only over HTTPS. Tells browsers never to use plain HTTP for this site.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin")));

        return http.build();
    }

    /**
     * New and changed passwords are hashed with Argon2id (OWASP settings: 19 MiB memory,
     * 2 iterations). Older bcrypt hashes still work and are re-hashed with Argon2id the next
     * time that person signs in.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder()));
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
