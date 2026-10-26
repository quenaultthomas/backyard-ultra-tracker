package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresseInvalideException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocageInvalideException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Politique de blocage des connexions par pseudo et plafond d'échecs par adresse, lus dans backyard.connexion.*
 * (variables CONNEXION_ECHECS_MAX, CONNEXION_BLOCAGE_SECONDES, CONNEXION_ECHECS_PAR_IP_MAX et
 * CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES). Les bornes sont validées par le domaine ; une valeur invalide empêche
 * le démarrage avec un message nommant la propriété. Valeurs par défaut dans application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class ConnexionConfiguration {

    static final String PROPRIETE_ECHECS_MAX = "backyard.connexion.echecs-max";
    static final String PROPRIETE_BLOCAGE_SECONDES = "backyard.connexion.blocage-secondes";
    static final String PROPRIETE_ECHECS_PAR_IP_MAX = "backyard.connexion.echecs-par-ip-max";
    static final String PROPRIETE_ECHECS_PAR_IP_FENETRE = "backyard.connexion.echecs-par-ip-fenetre-secondes";

    private static final Logger JOURNAL = LoggerFactory.getLogger(ConnexionConfiguration.class);

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

    /**
     * Propriété absente (contexte sans application.yml) : défaut du domaine. Présente mais vide ou invalide : refus
     * au démarrage, jamais de repli silencieux.
     */
    @Bean
    PlafondEchecsParAdresse plafondEchecsParAdresse(
            @Value("${" + PROPRIETE_ECHECS_PAR_IP_MAX + ":" + PlafondEchecsParAdresse.ECHECS_MAX_PAR_DEFAUT + "}")
            String echecsMax,
            @Value("${" + PROPRIETE_ECHECS_PAR_IP_FENETRE + ":" + PlafondEchecsParAdresse.FENETRE_SECONDES_PAR_DEFAUT + "}")
            String fenetreSecondes) {
        PlafondEchecsParAdresse plafond;
        try {
            plafond = new PlafondEchecsParAdresse(
                    entierBorne(PROPRIETE_ECHECS_PAR_IP_MAX, echecsMax, PlafondEchecsParAdresse.ECHECS_MAX_BORNE_MAX),
                    entierBorne(PROPRIETE_ECHECS_PAR_IP_FENETRE, fenetreSecondes,
                            PlafondEchecsParAdresse.FENETRE_SECONDES_BORNE_MAX));
        } catch (PlafondEchecsParAdresseInvalideException exception) {
            throw new IllegalStateException("Plafond d'échecs de connexion par adresse mal configuré ("
                    + PROPRIETE_ECHECS_PAR_IP_MAX + ", " + PROPRIETE_ECHECS_PAR_IP_FENETRE + ") : "
                    + exception.getMessage(), exception);
        }
        JOURNAL.info("Plafond d'échecs de connexion par adresse : {} échecs par {} s",
                plafond.echecsMax(), plafond.fenetreSecondes());
        return plafond;
    }

    private static int entierBorne(String propriete, String valeur, int borneMax) {
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(propriete + " doit être un entier de 1 à " + borneMax
                    + " (reçu : « " + valeur + " »).", exception);
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
