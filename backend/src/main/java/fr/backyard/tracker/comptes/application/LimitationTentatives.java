package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;

/**
 * Déroulé commun de la limitation des tentatives (connexion et changement de mot de passe) : même
 * registre, même clé (pseudo normalisé), mêmes messages de journal. Les règles restent dans
 * {@link TentativesConnexion} et {@link PolitiqueBlocage}.
 */
final class LimitationTentatives {

    private static final Logger JOURNAL = System.getLogger(LimitationTentatives.class.getName());

    private final RegistreTentativesConnexion registre;
    private final PolitiqueBlocage politique;

    LimitationTentatives(RegistreTentativesConnexion registre, PolitiqueBlocage politique) {
        this.registre = registre;
        this.politique = politique;
    }

    /** @throws ConnexionBloqueeException si la clé est bloquée à cet instant */
    void verifierNonBloquee(String cle, Instant maintenant) {
        Duration restant = tempsDeBlocage(cle, maintenant);
        if (!restant.isZero()) {
            throw new ConnexionBloqueeException(restant);
        }
    }

    /** Temps restant du blocage de la clé ; nul si elle n'est pas bloquée. */
    Duration tempsDeBlocage(String cle, Instant maintenant) {
        Duration restant = registre.constater(cle, maintenant).tempsRestant(maintenant);
        if (!restant.isZero()) {
            JOURNAL.log(Level.INFO, "Connexion refusée : blocage en cours");
        }
        return restant;
    }

    void enregistrerEchec(String cle, Instant maintenant) {
        TentativesConnexion apres = registre.enregistrerEchec(cle, maintenant, politique);
        if (apres.blocageDeclencheA(maintenant)) {
            JOURNAL.log(Level.WARNING, "Connexion bloquée temporairement");
        }
    }

    void oublier(String cle) {
        registre.effacer(cle);
    }
}
