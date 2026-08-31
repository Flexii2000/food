package com.fherrmann.food.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Protects the whole application (UI + API) with the shared private-mode cookie of
 * fherrmann.com - see {@link PrivateCookie}.
 *
 * <p>nginx already refuses requests without that cookie before they ever reach here
 * (see {@code deploy/nginx-food.fherrmann.com.conf}). Checking it a second time inside
 * the app is not redundant: it keeps the app from being wide open to anything on the
 * host that can reach {@code 127.0.0.1:48180} directly, and it means a mistake in the
 * server block cannot quietly expose a food diary.
 *
 * <p>There is deliberately no login, no registration and no {@code /setup} endpoint
 * here: the cookie is issued centrally on fherrmann.com, and duplicating that would
 * mean a second place able to mint access.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PrivateCookieAuthFilter privateCookieAuthFilter(@Value("${food.security.token}") String token) {
        return new PrivateCookieAuthFilter(token);
    }

    /**
     * CORS for the two endpoints other fherrmann.com subdomains read: the kcal overlay
     * in the weight tracker's charts and the statusboard card. Credentials are allowed
     * because the private-mode cookie has to travel with them - which forces an
     * explicit origin list, since the spec forbids pairing credentials with {@code *}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${food.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/food/daily", config);
        source.registerCorsConfiguration("/api/food/status", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PrivateCookieAuthFilter privateCookieAuthFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(privateCookieAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, ex) -> {
                    String accept = request.getHeader("Accept");
                    if (accept != null && accept.contains("text/html")) {
                        // Same behaviour as the weight tracker: a browser that lost its
                        // cookie gets sent back to the place that hands one out.
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("text/html;charset=UTF-8");
                        response.getWriter().write("""
                                <!doctype html>
                                <html><body>
                                <script>
                                  alert('Nicht autorisiert');
                                  window.location.href = 'https://fherrmann.com';
                                </script>
                                </body></html>
                                """);
                    } else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                }))
                // Auth is a single shared-secret token in a SameSite=Lax cookie, not
                // forms/sessions. Lax withholds it from cross-site requests, which covers
                // the state-changing endpoints, so CSRF protection is not needed on top.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
