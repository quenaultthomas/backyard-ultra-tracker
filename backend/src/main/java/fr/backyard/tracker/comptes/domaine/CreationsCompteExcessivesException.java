package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Création de Compte coureur refusée : l'adresse cliente a atteint son plafond de créations (SEC-15). Message fixe,
 * sans pseudo, mot de passe ni adresse.
 */
public class CreationsCompteExcessivesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration tempsRestant;

    public CreationsCompteExcessivesException(Duration tempsRestant) {
        super("Trop de créations de compte depuis cette adresse. Réessayez dans quelques instants.");
        this.tempsRestant = DureeEnSecondes.arrondieALaSecondeSuperieure(tempsRestant);
    }

    /** Secondes à attendre avant une nouvelle tentative, arrondies au supérieur, au moins 1. */
    public long reessayerDansSecondes() {
        return tempsRestant.toSeconds();
    }
}
