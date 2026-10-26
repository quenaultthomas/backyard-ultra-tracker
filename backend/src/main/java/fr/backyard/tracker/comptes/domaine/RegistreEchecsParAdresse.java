package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;

/**
 * Port sortant : échecs de connexion par adresse cliente (SEC-14). Chaque opération est atomique pour une
 * adresse. Aucune opération d'effacement : seul l'écoulement de la fenêtre libère une adresse.
 */
public interface RegistreEchecsParAdresse {

    /** État de l'adresse à l'instant donné ({@link EchecsParAdresse#aucun()} si inconnue ou oubliée). */
    EchecsParAdresse constater(AdresseSuivie adresse, Instant maintenant);

    /** Enregistre un échec selon le plafond et retourne le nouvel état de l'adresse. */
    EchecsParAdresse enregistrerEchec(AdresseSuivie adresse, Instant maintenant, PlafondEchecsParAdresse plafond);
}
