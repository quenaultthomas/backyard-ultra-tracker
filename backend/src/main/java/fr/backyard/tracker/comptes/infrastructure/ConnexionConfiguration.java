package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocageInvalideException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Politique de blocage des connexions, lue dans backyard.connexion.* (variables CONNEXION_ECHECS_MAX et
 * CONNEXION_BLOCAGE_SECONDES). Les bornes sont validées par le domaine ; une valeur invalide empêche
 * le démarrage avec un message nommant la propriété. Valeurs par défaut dans application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class ConnexionConfiguration {

    static final String PROPRIETE_ECHECS_MAX = "backyard.connexion.echecs-max";
    static final String PROPRIETE_BLOCAGE_SECONDES = "backyard.connexion.blocage-secondes";

    @Bean
    PolitiqueBlocage politiqueBlocage(
            @Value("${" + PROPRIETE_ECHECS_MAX + "}") String echecsMax,
            @Value("${" + PROPRIETE_BLOCAGE_SECONDES + "}") String blocageSecondes) {
        try {
            return new PolitiqueBlocage(entier(PROPRIETE_ECHECS_MAX, echecsMax),
                    entier(PROPRIETE_BLOCAGE_SECONDES, blocageSecondes));
        } catch (PolitiqueBlocageInvalideException exception) {
            throw new IllegalStateException("Limitation des connexions mal configurée (" + PROPRIETE_ECHECS_MAX
                    + ", " + PROPRIETE_BLOCAGE_SECONDES + ") : " + exception.getMessage(), exception);
        }
    }

    private static int entier(String propriete, String valeur) {
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    propriete + " doit être un entier supérieur ou égal à 1 (reçu : « " + valeur + " »).", exception);
        }
    }
}
