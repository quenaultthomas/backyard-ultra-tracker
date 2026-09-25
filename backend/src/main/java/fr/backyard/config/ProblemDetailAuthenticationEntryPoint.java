package fr.backyard.config;

import fr.backyard.api.error.ApiErrorCode;
import fr.backyard.api.error.ProblemDetailFactory;
import fr.backyard.api.error.ProblemDetailResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Réponse 401 {@code UNAUTHENTICATED} en ProblemDetail (RG30 inc. 3), sans en-tête
 * {@code WWW-Authenticate} pour ne pas déclencher la fenêtre d'identification du navigateur (PO20).
 * Le détail ne dit jamais si c'est le nom ou le mot de passe qui est faux.
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);

    private final ProblemDetailResponseWriter writer;

    public ProblemDetailAuthenticationEntryPoint(ProblemDetailResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String detail = authException instanceof InsufficientAuthenticationException
            ? "Authentification requise"
            : "Identifiants invalides";
        LOG.warn("Requête non authentifiée {} {} : {}", request.getMethod(), request.getRequestURI(), detail);
        writer.write(ProblemDetailFactory.create(ApiErrorCode.UNAUTHENTICATED, detail, request.getRequestURI()),
            response);
    }
}
