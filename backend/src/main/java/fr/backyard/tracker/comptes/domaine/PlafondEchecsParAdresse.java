package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Plafond d'échecs de connexion par adresse cliente sur une fenêtre glissante (SEC-14) : au plus
 * {@code echecsMax} échecs actifs par {@code fenetreSecondes} ; au-delà, les tentatives sont refusées.
 */
public record PlafondEchecsParAdresse(int echecsMax, int fenetreSecondes) {

    public static final int ECHECS_MAX_BORNE_MAX = 1000;
    public static final int FENETRE_SECONDES_BORNE_MAX = 3600;
    public static final int ECHECS_MAX_PAR_DEFAUT = 20;
    public static final int FENETRE_SECONDES_PAR_DEFAUT = 60;
    private static final int BORNE_MIN = 1;

    public PlafondEchecsParAdresse {
        verifierBornes("echecsMax", echecsMax, ECHECS_MAX_BORNE_MAX);
        verifierBornes("fenetreSecondes", fenetreSecondes, FENETRE_SECONDES_BORNE_MAX);
    }

    public static PlafondEchecsParAdresse parDefaut() {
        return new PlafondEchecsParAdresse(ECHECS_MAX_PAR_DEFAUT, FENETRE_SECONDES_PAR_DEFAUT);
    }

    public Duration fenetre() {
        return Duration.ofSeconds(fenetreSecondes);
    }

    private static void verifierBornes(String parametre, int valeur, int borneMax) {
        if (valeur < BORNE_MIN || valeur > borneMax) {
            throw new PlafondEchecsParAdresseInvalideException(parametre + " doit être un entier de " + BORNE_MIN
                    + " à " + borneMax + " (reçu : " + valeur + ").");
        }
    }
}
