package com.fherrmann.food.security;

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

/**
 * Authenticates requests carrying a valid {@link PrivateCookie#NAME} cookie, and leaves
 * everything else unauthenticated.
 */
public class PrivateCookieAuthFilter extends OncePerRequestFilter {

    private final String token;

    public PrivateCookieAuthFilter(String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasValidCookie(request)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "device", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }
        chain.doFilter(request, response);
    }

    /**
     * Auch beim ERROR-Dispatch pruefen.
     *
     * <p>{@link OncePerRequestFilter} laesst den Filter dort per Vorgabe aus. Weil
     * die Sitzung zustandslos ist, ist der Sicherheitskontext beim Rendern der
     * Fehlerseite dann leer - Spring Security lehnt den internen Weiterlauf nach
     * {@code /error} ab und ersetzt den echten Status durch 403. Aus einem 400
     * ("Menge fehlt") wird so ein "nicht autorisiert", und zwar bei jedem
     * Fehlerpfad der ganzen Anwendung.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private boolean hasValidCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (PrivateCookie.NAME.equals(cookie.getName()) && PrivateCookie.matches(cookie.getValue(), token)) {
                return true;
            }
        }
        return false;
    }
}
