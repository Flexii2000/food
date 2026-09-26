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
 * Protects the whole application (UI + API) with long-lived tokens instead of a login.
 *
 * <p>Originally only the shared private-mode cookie of fherrmann.com ({@link PrivateCookie}),
 * checked by nginx in front and here a second time. Since 2026-09 further people have their
 * own token ({@code health.tokens}, shared with the weight tracker) - as a cookie or as
 * {@code Authorization: Bearer} from the Android app - and each of them sees only their own
 * diary. nginx no longer gates the domain, because it cannot tell a valid personal token
 * from an invalid one; this filter chain is now the only gate, exactly like the shopping
 * list's. See {@link PrivateCookieAuthFilter} and {@link HealthUsers}.
 *
 * <p>{@code /setup} only ever issues a personal token's cookie. The private-mode cookie
 * stays issued centrally on fherrmann.com - a second place able to mint that one would
 * open far more than this app.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PrivateCookieAuthFilter privateCookieAuthFilter(
            @Value("${food.security.token}") String token, HealthUsers users) {
        return new PrivateCookieAuthFilter(token, users);
    }

    /**
     * CORS for the handful of endpoints other fherrmann.com subdomains read: the kcal
     * overlay in the weight tracker's charts ({@code /daily} und {@code /targets} -
     * die Werte und die Ziellinie dazu) und die Statusboard-Karte
     * ({@code /status}). Credentials are allowed because the private-mode cookie has
     * to travel with them - which forces an explicit origin list, since the spec
     * forbids pairing credentials with {@code *}.
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
        source.registerCorsConfiguration("/api/food/daily-average", config);
        source.registerCorsConfiguration("/api/food/targets", config);
        source.registerCorsConfiguration("/api/food/status", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PrivateCookieAuthFilter privateCookieAuthFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/setup").permitAll()
                        .anyRequest().authenticated())
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
                // Auth is a token in a SameSite=Lax cookie or a Bearer header, not
                // forms/sessions. Lax withholds the cookie from cross-site requests, which
                // covers the state-changing endpoints, and no browser attaches a Bearer
                // header on its own, so CSRF protection is not needed on top.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
