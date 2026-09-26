package com.fherrmann.food.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Einrichtung eines Browsers mit einem persoenlichen Healthy-Token:
 * {@code /setup?token=<token>} setzt das Cookie {@code health_token} fuer die ganze
 * Domain und leitet auf die Oberflaeche weiter. Derselbe Link oeffnet damit auch den
 * Weight Tracker.
 *
 * <p>Nur fuer persoenliche Token. Der Privat-Cookie wird weiterhin ausschliesslich
 * auf fherrmann.com ausgestellt - er oeffnet weit mehr als diesen Dienst.
 */
@RestController
public class SetupController {

    private final HealthUsers users;
    private final String cookieDomain;

    public SetupController(HealthUsers users, @Value("${health.cookie-domain:}") String cookieDomain) {
        this.users = users;
        this.cookieDomain = cookieDomain;
    }

    @GetMapping("/setup")
    public ResponseEntity<Void> setup(@RequestParam(name = "token", required = false) String token,
                                      HttpServletRequest request) {
        if (users.nameFor(token).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, HealthCookie.issue(token, cookieDomain, request).toString())
                .location(URI.create("/"))
                .build();
    }
}
