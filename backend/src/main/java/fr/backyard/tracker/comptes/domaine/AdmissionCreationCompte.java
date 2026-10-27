package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Issue de l'admission d'une tentative de création de Compte pour une adresse (SEC-15) : admise (et, le cas échéant,
 * celle qui vient de faire atteindre le plafond), ou refusée avec le temps restant avant la libération de l'adresse.
 */
public record AdmissionCreationCompte(boolean admise, boolean plafondAtteint, Duration tempsRestant) {

    public static AdmissionCreationCompte admise(boolean plafondAtteint) {
        return new AdmissionCreationCompte(true, plafondAtteint, Duration.ZERO);
    }

    public static AdmissionCreationCompte refusee(Duration tempsRestant) {
        return new AdmissionCreationCompte(false, false, tempsRestant);
    }
}
