package fr.backyard.tracker.comptes.exposition;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Dernier recours : toute exception qu'aucun autre gestionnaire ne traduit donne un 500 {@code ProblemDetail}
 * générique (contrat 1.1 RG13). Priorité la plus basse : les gestionnaires spécialisés, d'ordre explicite plus élevé,
 * sont toujours consultés avant.
 *
 * <p>Ni la réponse ni le journal ne reprennent le message des exceptions, qui peut contenir un pseudo ou une valeur
 * reçue (violation de contrainte, panne) : le journal ne garde que les types et l'endroit où chacune est levée.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GestionnaireErreursInattendues {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");
    private static final Logger JOURNAL = LoggerFactory.getLogger(GestionnaireErreursInattendues.class);

    @ExceptionHandler(Exception.class)
    ProblemDetail erreurInattendue(Exception exception) {
        JOURNAL.error("Erreur inattendue : {}", decrire(exception));
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Une erreur inattendue est survenue. Réessayez plus tard.");
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle("Erreur interne");
        probleme.setProperty("code", "ERREUR_INTERNE");
        return probleme;
    }

    /** Chaîne des causes, par type et lieu de levée, sans aucun message. */
    private static String decrire(Throwable exception) {
        List<String> causes = new ArrayList<>();
        for (Throwable cause = exception; cause != null && causes.size() < 10; cause = cause.getCause()) {
            StackTraceElement[] pile = cause.getStackTrace();
            causes.add(cause.getClass().getName() + (pile.length > 0 ? " at " + pile[0] : ""));
        }
        return String.join(" <- ", causes);
    }
}
