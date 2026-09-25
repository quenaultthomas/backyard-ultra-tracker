package fr.backyard.api.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;

import java.io.IOException;

/**
 * Écrit un ProblemDetail en {@code application/problem+json} directement dans la réponse servlet,
 * pour les erreurs produites hors de Spring MVC (chaîne de filtres de sécurité). Utilise le même
 * convertisseur Jackson que Spring MVC, qui aplatit les propriétés d'extension du ProblemDetail.
 */
public class ProblemDetailResponseWriter {

    private final JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();

    public void write(ProblemDetail problem, HttpServletResponse response) throws IOException {
        ServletServerHttpResponse output = new ServletServerHttpResponse(response);
        output.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
        converter.write(problem, MediaType.APPLICATION_PROBLEM_JSON, output);
        output.flush();
    }
}
