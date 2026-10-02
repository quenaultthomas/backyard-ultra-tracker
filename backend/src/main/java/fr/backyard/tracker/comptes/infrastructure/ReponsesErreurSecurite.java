package fr.backyard.tracker.comptes.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Réponses ProblemDetail des refus prononcés par Spring Security, avant tout contrôleur :
 * 401 sans authentification (sans en-tête WWW-Authenticate), 403 sur jeton CSRF absent ou invalide.
 */
@Component
public class ReponsesErreurSecurite implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public ReponsesErreurSecurite(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest requete, HttpServletResponse reponse,
                         AuthenticationException exception) throws IOException {
        ecrire(requete, reponse, HttpStatus.UNAUTHORIZED, "Authentification requise", "Vous devez être connecté.",
                "NON_AUTHENTIFIE");
    }

    @Override
    public void handle(HttpServletRequest requete, HttpServletResponse reponse,
                       AccessDeniedException exception) throws IOException {
        if (exception instanceof CsrfException) {
            ecrire(requete, reponse, HttpStatus.FORBIDDEN, "Accès refusé", "Jeton CSRF absent ou invalide.",
                    "CSRF_INVALIDE");
        } else {
            ecrire(requete, reponse, HttpStatus.FORBIDDEN, "Accès refusé", "Vous n'avez pas accès à cette ressource.",
                    "ACCES_REFUSE");
        }
    }

    private void ecrire(HttpServletRequest requete, HttpServletResponse reponse, HttpStatus statut, String titre,
                        String detail, String code) throws IOException {
        Map<String, Object> probleme = new LinkedHashMap<>();
        probleme.put("type", "about:blank");
        probleme.put("title", titre);
        probleme.put("status", statut.value());
        probleme.put("detail", detail);
        probleme.put("instance", requete.getRequestURI());
        probleme.put("code", code);
        reponse.setStatus(statut.value());
        reponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        reponse.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(reponse.getOutputStream(), probleme);
    }
}
