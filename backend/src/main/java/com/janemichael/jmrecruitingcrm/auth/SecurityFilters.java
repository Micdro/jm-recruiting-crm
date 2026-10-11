package com.janemichael.jmrecruitingcrm.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/** Filters added to the security chain in SecurityConfig. Not Spring beans, so they only run there. */
final class SecurityFilters {

    private SecurityFilters() {
    }

    /** The path within the app, independent of how the servlet is mapped. */
    static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    static void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error
                + "\",\"messages\":[\"" + message + "\"]}");
    }

    /** Refuses sign-in attempts while an account or address is locked out by {@link LoginThrottle}. */
    static final class LoginThrottleFilter extends OncePerRequestFilter {

        private final LoginThrottle throttle;

        LoginThrottleFilter(LoginThrottle throttle) {
            this.throttle = throttle;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !("POST".equals(request.getMethod()) && "/api/auth/login".equals(path(request)));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Duration blocked = throttle.blockedFor(request.getParameter("email"), request.getRemoteAddr());
            if (!blocked.isZero()) {
                long minutes = Math.max(1, (blocked.toSeconds() + 59) / 60);
                response.setHeader("Retry-After", String.valueOf(blocked.toSeconds()));
                writeError(response, 429, "Too many attempts",
                        "Too many failed sign-in attempts. Try again in " + minutes
                                + (minutes == 1 ? " minute." : " minutes."));
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Ends the session of anyone whose account was deactivated or removed, on their next request,
     * instead of letting it run until it expires.
     */
    static final class ActiveUserFilter extends OncePerRequestFilter {

        private final AppUserRepository userRepository;

        ActiveUserFilter(AppUserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !path(request).startsWith("/api/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean signedIn = authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);

            if (signedIn) {
                boolean active = userRepository.findByEmailIgnoreCase(authentication.getName())
                        .map(AppUser::isActive)
                        .orElse(false);
                if (!active) {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    SecurityContextHolder.clearContext();
                    writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                            "Your account is no longer active");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
