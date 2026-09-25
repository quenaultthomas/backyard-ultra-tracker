package fr.backyard.config;

import fr.backyard.api.error.ApiErrorCode;
import fr.backyard.api.error.ProblemDetailFactory;
import fr.backyard.api.error.ProblemDetailResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Réponse 403 {@code ACCESS_DENIED} en ProblemDetail (RG30 inc. 3) pour un appelant authentifié
 * dont le rôle n'est pas autorisé sur le chemin demandé.
 */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);
    private static final String ROLE_PREFIX = "ROLE_";

    private final ProblemDetailResponseWriter writer;

    public ProblemDetailAccessDeniedHandler(ProblemDetailResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String detail = "Accès refusé pour le rôle " + callerRoles() + " sur ce chemin";
        LOG.warn("Accès refusé {} {} : {}", request.getMethod(), request.getRequestURI(), detail);
        writer.write(ProblemDetailFactory.create(ApiErrorCode.ACCESS_DENIED, detail, request.getRequestURI()),
            response);
    }

    /** Rôles de l'appelant sans le préfixe ROLE_ ; les autres autorités (facteurs...) sont ignorées. */
    private static String callerRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "inconnu";
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .filter(authority -> authority.startsWith(ROLE_PREFIX))
            .map(authority -> authority.substring(ROLE_PREFIX.length()))
            .sorted()
            .collect(Collectors.joining(", "));
    }
}
