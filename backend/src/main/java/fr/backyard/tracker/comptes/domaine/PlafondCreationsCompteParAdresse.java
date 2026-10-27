package fr.backyard.tracker.comptes.domaine;

/**
 * Plafond de créations de Comptes coureur par adresse cliente sur une fenêtre glissante (SEC-15) : au plus
 * {@code creationsMax} tentatives admises par {@code fenetreSecondes}. La fenêtre glissante elle-même est celle de
 * {@link EchecsParAdresse}, appliquée via {@link #enFenetreGlissante()} ; les bornes sont celles de
 * {@link PlafondEchecsParAdresse}.
 */
public record PlafondCreationsCompteParAdresse(int creationsMax, int fenetreSecondes) {

    public static final int CREATIONS_MAX_BORNE_MAX = PlafondEchecsParAdresse.ECHECS_MAX_BORNE_MAX;
    public static final int FENETRE_SECONDES_BORNE_MAX = PlafondEchecsParAdresse.FENETRE_SECONDES_BORNE_MAX;
    public static final int CREATIONS_MAX_PAR_DEFAUT = 10;
    public static final int FENETRE_SECONDES_PAR_DEFAUT = 60;
    private static final int BORNE_MIN = 1;

    public PlafondCreationsCompteParAdresse {
        verifierBornes("creationsMax", creationsMax, CREATIONS_MAX_BORNE_MAX);
        verifierBornes("fenetreSecondes", fenetreSecondes, FENETRE_SECONDES_BORNE_MAX);
    }

    public static PlafondCreationsCompteParAdresse parDefaut() {
        return new PlafondCreationsCompteParAdresse(CREATIONS_MAX_PAR_DEFAUT, FENETRE_SECONDES_PAR_DEFAUT);
    }

    /** Le même plafond exprimé pour la fenêtre glissante de {@link EchecsParAdresse}, seule définition de la règle. */
    public PlafondEchecsParAdresse enFenetreGlissante() {
        return new PlafondEchecsParAdresse(creationsMax, fenetreSecondes);
    }

    private static void verifierBornes(String parametre, int valeur, int borneMax) {
        if (valeur < BORNE_MIN || valeur > borneMax) {
            throw new PlafondCreationsCompteInvalideException(parametre + " doit être un entier de " + BORNE_MIN
                    + " à " + borneMax + " (reçu : " + valeur + ").");
        }
    }
}
