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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Meldet an, wer einen gueltigen Token mitbringt, und setzt den Namen der Person
 * als Principal - Controller und Service lesen daran ab, wessen Tagebuch gemeint ist.
 *
 * <p>Drei Wege, in dieser Reihenfolge:
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} - die Android-App</li>
 *   <li>Cookie {@link HealthCookie#NAME} - ein Browser, eingerichtet ueber einen
 *       Healthy-Setup-Link</li>
 *   <li>Cookie {@link PrivateCookie#NAME} - der Privat-Cookie von fherrmann.com:
 *       Felix' Browser, die iPhone-App, Habits und das Statusboard; das ist die
 *       Eigentuemerin</li>
 * </ol>
 * Der persoenliche Token schlaegt den Privat-Cookie: den hat Felix' Browser
 * immer, und sonst liesse sich dort nie pruefen, was eine andere Person sieht.
 * Alles andere bleibt unangemeldet.
 */
public class PrivateCookieAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final String token;
    private final HealthUsers users;

    public PrivateCookieAuthFilter(String token, HealthUsers users) {
        this.token = token;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> person = fromBearer(request)
                .or(() -> cookies(request, HealthCookie.NAME).stream()
                        .map(users::nameFor)
                        .flatMap(Optional::stream)
                        .findFirst())
                .or(() -> cookies(request, PrivateCookie.NAME).stream()
                        .anyMatch(value -> PrivateCookie.matches(value, token))
                        ? Optional.of(users.owner())
                        : Optional.empty());
        person.ifPresent(name -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        name, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
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

    private Optional<String> fromBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return users.nameFor(header.substring(BEARER.length()).trim());
    }

    /**
     * Alle Werte eines Cookies. Es kann mehrere gleichnamige geben (eins fuer den
     * Host, eins fuer die Domain) - gezaehlt wird jedes, das passt, nicht nur das
     * erste.
     */
    private static List<String> cookies(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null) {
                values.add(cookie.getValue());
            }
        }
        return values;
    }
}
