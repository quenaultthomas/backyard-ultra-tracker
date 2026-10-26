package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.EchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreEchecsParAdresse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Déroulé du plafond d'échecs de connexion par adresse (SEC-14). Les règles restent dans {@link EchecsParAdresse}
 * et {@link PlafondEchecsParAdresse}. Adresse inconnue (hors requête HTTP) : ni contrôle ni comptage. Journal sans
 * pseudo, mot de passe ni adresse.
 */
final class LimitationParAdresse {

    private static final Logger JOURNAL = System.getLogger(LimitationParAdresse.class.getName());

    private final RegistreEchecsParAdresse registre;
    private final PlafondEchecsParAdresse plafond;

    LimitationParAdresse(RegistreEchecsParAdresse registre, PlafondEchecsParAdresse plafond) {
        this.registre = registre;
        this.plafond = plafond;
    }

    /** Temps restant avant que l'adresse ne soit de nouveau admise ; nul si elle ne l'est pas ou est inconnue. */
    Duration tempsDePlafond(Optional<AdresseSuivie> adresse, Instant maintenant) {
        Duration restant = adresse
                .map(suivie -> registre.constater(suivie, maintenant).tempsRestant(maintenant, plafond))
                .orElse(Duration.ZERO);
        if (!restant.isZero()) {
            JOURNAL.log(Level.DEBUG, "Connexion refusée : plafond d'échecs par adresse atteint");
        }
        return restant;
    }

    void enregistrerEchec(Optional<AdresseSuivie> adresse, Instant maintenant) {
        adresse.map(suivie -> registre.enregistrerEchec(suivie, maintenant, plafond))
                .filter(apres -> apres.atteintLePlafondA(maintenant, plafond))
                .ifPresent(apres -> JOURNAL.log(Level.WARNING, "Connexion : plafond d'échecs par adresse atteint"));
    }
}
