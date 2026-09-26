package com.fherrmann.food.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Das Cookie mit dem Healthy-Token einer Person.
 *
 * <p>Gesetzt auf die ganze Domain ({@code Domain=fherrmann.com}), damit ein
 * einziger Setup-Link Kalorienzaehler <b>und</b> Weight Tracker oeffnet - und
 * damit die beiden Weboberflaechen sich gegenseitig lesen koennen (kcal-Overlay,
 * Gewichtskurve), wie sie es mit Felix' Cookies auch tun. Dieselbe Klasse steht
 * im Weight Tracker.
 */
final class HealthCookie {

    static final String NAME = "health_token";
    static final Duration MAX_AGE = Duration.ofDays(5 * 365);

    private HealthCookie() {
    }

    /**
     * Das Cookie fuer diesen Token.
     *
     * <p>Die Domain nur, wenn die Anfrage auch wirklich darunter kam: lokal
     * ({@code localhost}) wuerde der Browser ein Cookie fuer
     * {@code fherrmann.com} stillschweigend verwerfen, und die Einrichtung
     * saehe aus wie ein falscher Token.
     */
    static ResponseCookie issue(String token, String domain, HttpServletRequest request) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(MAX_AGE);
        String host = request.getServerName();
        if (domain != null && !domain.isBlank() && host != null
                && (host.equals(domain) || host.endsWith("." + domain))) {
            cookie.domain(domain);
        }
        return cookie.build();
    }
}
