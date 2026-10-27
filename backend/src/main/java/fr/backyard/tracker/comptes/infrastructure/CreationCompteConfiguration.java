package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteInvalideException;
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plafond de créations de Comptes coureur par adresse (SEC-15), lu dans backyard.creation-compte.* (variables
 * CREATION_COMPTE_PAR_IP_MAX et CREATION_COMPTE_PAR_IP_FENETRE_SECONDES). Les bornes sont validées par le domaine ;
 * une valeur invalide empêche le démarrage avec un message nommant la propriété. Valeurs par défaut dans
 * application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class CreationCompteConfiguration {

    static final String PROPRIETE_PAR_IP_MAX = "backyard.creation-compte.par-ip-max";
    static final String PROPRIETE_PAR_IP_FENETRE = "backyard.creation-compte.par-ip-fenetre-secondes";

    private static final Logger JOURNAL = LoggerFactory.getLogger(CreationCompteConfiguration.class);

    /**
     * Propriété absente (contexte sans application.yml) : défaut du domaine. Présente mais vide ou invalide : refus
     * au démarrage, jamais de repli silencieux.
     */
    @Bean
    PlafondCreationsCompteParAdresse plafondCreationsCompteParAdresse(
            @Value("${" + PROPRIETE_PAR_IP_MAX + ":" + PlafondCreationsCompteParAdresse.CREATIONS_MAX_PAR_DEFAUT + "}")
            String creationsMax,
            @Value("${" + PROPRIETE_PAR_IP_FENETRE + ":"
                    + PlafondCreationsCompteParAdresse.FENETRE_SECONDES_PAR_DEFAUT + "}")
            String fenetreSecondes) {
        PlafondCreationsCompteParAdresse plafond;
        try {
            plafond = new PlafondCreationsCompteParAdresse(
                    entier(PROPRIETE_PAR_IP_MAX, creationsMax, PlafondCreationsCompteParAdresse.CREATIONS_MAX_BORNE_MAX),
                    entier(PROPRIETE_PAR_IP_FENETRE, fenetreSecondes,
                            PlafondCreationsCompteParAdresse.FENETRE_SECONDES_BORNE_MAX));
        } catch (PlafondCreationsCompteInvalideException exception) {
            throw new IllegalStateException("Plafond de créations de compte par adresse mal configuré ("
                    + PROPRIETE_PAR_IP_MAX + ", " + PROPRIETE_PAR_IP_FENETRE + ") : " + exception.getMessage(),
                    exception);
        }
        JOURNAL.info("Plafond de créations de compte par adresse : {} créations par {} s",
                plafond.creationsMax(), plafond.fenetreSecondes());
        return plafond;
    }

    private static int entier(String propriete, String valeur, int borneMax) {
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(propriete + " doit être un entier de 1 à " + borneMax
                    + " (reçu : « " + valeur + " »).", exception);
        }
    }
}
