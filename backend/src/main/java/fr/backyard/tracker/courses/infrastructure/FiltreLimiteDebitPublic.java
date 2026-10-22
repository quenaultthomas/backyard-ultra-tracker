package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.partage.infrastructure.AdresseCliente;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applique {@link LimiteDebitPublic} avant Spring Security et avant tout accès base : une requête refusée reçoit 429
 * {@code TROP_DE_REQUETES} (ProblemDetail, {@code Retry-After}, {@code Cache-Control: no-store}) sans donnée de
 * Course ; les autres continuent sans modification.
 */
final class FiltreLimiteDebitPublic extends OncePerRequestFilter {

    private final LimiteDebitPublic limiteDebitPublic;
    private final AdresseCliente adresseCliente;
    private final JsonMapper jsonMapper;

    FiltreLimiteDebitPublic(LimiteDebitPublic limiteDebitPublic, AdresseCliente adresseCliente,
                            JsonMapper jsonMapper) {
        this.limiteDebitPublic = limiteDebitPublic;
        this.adresseCliente = adresseCliente;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {
        LimiteDebitPublic.Decision decision = limiteDebitPublic.verifier(requete.getMethod(),
                UrlPathHelper.defaultInstance.getPathWithinApplication(requete), adresseCliente.de(requete));
        if (decision.autorisee()) {
            suite.doFilter(requete, reponse);
        } else {
            refuser(requete, reponse, decision.reessayerDansSecondes());
        }
    }

    private void refuser(HttpServletRequest requete, HttpServletResponse reponse, int reessayerDansSecondes)
            throws IOException {
        Map<String, Object> probleme = new LinkedHashMap<>();
        probleme.put("type", "about:blank");
        probleme.put("title", "Trop de requêtes");
        probleme.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        probleme.put("detail", "Trop de requêtes. Réessayez dans quelques secondes.");
        probleme.put("instance", requete.getRequestURI());
        probleme.put("code", "TROP_DE_REQUETES");
        probleme.put("reessayerDansSecondes", reessayerDansSecondes);
        reponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        reponse.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(reessayerDansSecondes));
        reponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        reponse.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(reponse.getOutputStream(), probleme);
    }
}
