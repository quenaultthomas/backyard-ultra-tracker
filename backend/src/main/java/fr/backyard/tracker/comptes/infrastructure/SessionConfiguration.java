package fr.backyard.tracker.comptes.infrastructure;

import static fr.backyard.tracker.comptes.domaine.PolitiqueSession.PARAMETRE_ROLES_PRIVILEGIES;
import static fr.backyard.tracker.comptes.domaine.PolitiqueSession.SECONDES_MAX;
import static fr.backyard.tracker.comptes.domaine.PolitiqueSession.SECONDES_MIN;

import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import fr.backyard.tracker.comptes.domaine.PolitiqueSessionInvalideException;
import java.time.Duration;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Politique de session par rôle, lue dans backyard.session.* (variables SESSION_INACTIVITE_ADMIN_SECONDES et
 * SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES). Les bornes sont validées par le domaine ; une valeur invalide
 * empêche le démarrage avec un message nommant la propriété. Valeurs par défaut dans application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {

    static final String PROPRIETE_ADMIN = "backyard.session.inactivite-admin-secondes";
    static final String PROPRIETE_COUREUR_BENEVOLE = "backyard.session.inactivite-coureur-benevole-secondes";

    private static final Logger JOURNAL = LoggerFactory.getLogger(SessionConfiguration.class);

    @Bean
    PolitiqueSession politiqueSession(
            @Value("${" + PROPRIETE_ADMIN + "}") String inactiviteAdmin,
            @Value("${" + PROPRIETE_COUREUR_BENEVOLE + "}") String inactiviteCoureurBenevole) {
        PolitiqueSession politique = construire(secondes(PROPRIETE_ADMIN, inactiviteAdmin),
                secondes(PROPRIETE_COUREUR_BENEVOLE, inactiviteCoureurBenevole));
        JOURNAL.info("Politique de session : {} s d'inactivité pour les administrateurs, {} s pour les coureurs et bénévoles",
                politique.inactiviteRolesPrivilegies().toSeconds(), politique.inactiviteAutresRoles().toSeconds());
        return politique;
    }

    private static PolitiqueSession construire(Duration inactiviteAdmin, Duration inactiviteCoureurBenevole) {
        try {
            return new PolitiqueSession(inactiviteAdmin, inactiviteCoureurBenevole);
        } catch (PolitiqueSessionInvalideException exception) {
            throw new IllegalStateException("Politique de session mal configurée (" + proprietes(exception) + ") : "
                    + exception.getMessage(), exception);
        }
    }

    private static String proprietes(PolitiqueSessionInvalideException exception) {
        return exception.parametres().stream()
                .map(parametre -> PARAMETRE_ROLES_PRIVILEGIES.equals(parametre) ? PROPRIETE_ADMIN : PROPRIETE_COUREUR_BENEVOLE)
                .collect(Collectors.joining(", "));
    }

    private static Duration secondes(String propriete, String valeur) {
        try {
            return Duration.ofSeconds(Long.parseLong(valeur.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(propriete + " doit être un entier de " + SECONDES_MIN + " à " + SECONDES_MAX
                    + " (reçu : « " + valeur + " »).", exception);
        }
    }
}
