package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Politique de limitation des tentatives de connexion : nombre d'échecs consécutifs avant blocage,
 * et durée du blocage, qui est aussi la fenêtre d'oubli des échecs partiels.
 */
public record PolitiqueBlocage(int echecsMax, int blocageSecondes) {

    public static final int ECHECS_MAX_PAR_DEFAUT = 5;
    public static final int BLOCAGE_SECONDES_PAR_DEFAUT = 900;
    private static final int BORNE_MIN = 1;

    public PolitiqueBlocage {
        verifierBorne("echecsMax", echecsMax);
        verifierBorne("blocageSecondes", blocageSecondes);
    }

    public static PolitiqueBlocage parDefaut() {
        return new PolitiqueBlocage(ECHECS_MAX_PAR_DEFAUT, BLOCAGE_SECONDES_PAR_DEFAUT);
    }

    public Duration blocage() {
        return Duration.ofSeconds(blocageSecondes);
    }

    private static void verifierBorne(String parametre, int valeur) {
        if (valeur < BORNE_MIN) {
            throw new PolitiqueBlocageInvalideException(
                    parametre + " doit être un entier supérieur ou égal à " + BORNE_MIN + " (reçu : " + valeur + ").");
        }
    }
}
