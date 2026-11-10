package com.fherrmann.food.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Die Tuer zu {@code /api/internal/**} - den Endpunkten, die nur andere Dienste auf
 * demselben Server rufen (Healthy-Vertrag §11.3).
 *
 * <p>Eigene Schranke statt einer Erweiterung des Personen-Filters, weil hier andere
 * Regeln gelten:
 * <ul>
 *   <li><b>Nur von innen.</b> Eine Anfrage mit {@code X-Real-IP} kam ueber nginx, also
 *       von aussen - sie bekommt 404, als gaebe es den Pfad nicht. nginx sperrt den
 *       Pfad zusaetzlich selbst; das hier haelt, falls diese Zeile dort einmal fehlt.</li>
 *   <li><b>Kein Cookie zaehlt.</b> Was der {@link PrivateCookieAuthFilter} davor erkannt
 *       hat, gilt auf diesen Pfaden nicht: der Privat-Cookie liegt in jedem von Felix'
 *       Browsern, und ein Browser soll hier nie etwas ausloesen koennen.</li>
 *   <li><b>Je Endpunkt ein eigener Schluessel.</b> {@code /push} nimmt nur den
 *       Bearer-Token der Eigentuemerin (den hat der Weight Tracker ohnehin, er liest
 *       dieselbe Token-Liste). {@code /recipe-dishes} nimmt nur den Dienst-Token der
 *       Einkaufsliste - der ist keine Person und oeffnet sonst nichts; so muss in der
 *       Einkaufsliste kein persoenlicher Token liegen.</li>
 * </ul>
 *
 * <p>Wer durchkommt, traegt genau das eine Recht fuer seinen Endpunkt
 * ({@link #PUSH} bzw. {@link #RECIPE_DISHES}); {@link SecurityConfig} verlangt es dort.
 */
public class InternalAccessFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/api/internal/";
    public static final String PUSH_PATH = "/api/internal/push";
    public static final String RECIPE_DISHES_PATH = "/api/internal/recipe-dishes";

    /** Das Recht, eine Mitteilung an die Geraete einer Person zu schicken. */
    public static final String PUSH = "INTERNAL_PUSH";
    /** Das Recht, ein Rezept als Gericht in Tagebuecher zu legen. */
    public static final String RECIPE_DISHES = "INTERNAL_RECIPE_DISHES";

    /** Als wer der Dienst-Token der Einkaufsliste angemeldet ist - bewusst kein gueltiger Personenname. */
    public static final String RECIPE_SERVICE = "internal:recipes";

    private static final String BEARER = "Bearer ";

    private final HealthUsers users;
    private final byte[] recipeToken;

    /**
     * @param recipeToken der Dienst-Token fuer {@code /recipe-dishes}; leer heisst, der
     *                    Endpunkt ist nicht eingerichtet und antwortet mit 404
     */
    public InternalAccessFilter(HealthUsers users, String recipeToken) {
        this.users = users;
        String token = recipeToken == null ? "" : recipeToken.trim();
        this.recipeToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = originalPath(request);
        if (!path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        // Hier zaehlt nur, was dieser Filter selbst prueft - nicht der Cookie oder
        // Token, den der Personen-Filter davor erkannt hat.
        SecurityContextHolder.getContext().setAuthentication(null);

        if (request.getHeader("X-Real-IP") != null) {
            notFound(response);
            return;
        }
        String bearer = bearer(request);
        if (path.equals(PUSH_PATH)) {
            if (bearer != null && users.nameFor(bearer).filter(users::isOwner).isPresent()) {
                authenticate(users.owner(), PUSH);
            }
        } else if (path.equals(RECIPE_DISHES_PATH)) {
            if (recipeToken.length == 0) {
                notFound(response);
                return;
            }
            if (bearer != null && MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8), recipeToken)) {
                authenticate(RECIPE_SERVICE, RECIPE_DISHES);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Auch beim ERROR-Dispatch pruefen - aus demselben Grund wie im
     * {@link PrivateCookieAuthFilter}: sonst waere der Sicherheitskontext beim Rendern
     * der Fehlerseite leer (der Dienst-Token ist keine Person), und aus einem 400 des
     * Endpunkts wuerde ein 403.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Der Pfad, um den es geht. Beim ERROR-Dispatch ist die Anfrage auf {@code /error}
     * umgeleitet; der urspruengliche Pfad steht dann im Attribut.
     */
    private static String originalPath(HttpServletRequest request) {
        Object forwarded = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = forwarded instanceof String original ? original : request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) {
            return "";
        }
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static void authenticate(String name, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    /**
     * Schlicht der Status, kein {@code sendError}: der loeste den Umweg ueber
     * {@code /error} aus, und dort wuerde aus dem 404 fuer eine unangemeldete Anfrage
     * ein 403 - genau die Auskunft ("hier ist etwas"), die es nicht geben soll.
     */
    private static void notFound(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
}
