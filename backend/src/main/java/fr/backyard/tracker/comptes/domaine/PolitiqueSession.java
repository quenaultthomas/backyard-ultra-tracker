package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;
import java.util.List;

/**
 * Politique de session par rôle (SEC-16) : durée d'inactivité au-delà de laquelle une session expire. Les rôles
 * privilégiés (ADMIN_MASTER, ADMIN) ont un délai au plus égal à celui des autres rôles (BENEVOLE, COUREUR).
 * Seule source de la règle : aucune autre classe ne choisit un délai selon le rôle.
 */
public record PolitiqueSession(Duration inactiviteRolesPrivilegies, Duration inactiviteAutresRoles) {

    public static final String PARAMETRE_ROLES_PRIVILEGIES = "inactiviteRolesPrivilegies";
    public static final String PARAMETRE_AUTRES_ROLES = "inactiviteAutresRoles";
    public static final long SECONDES_MIN = 1;
    public static final long SECONDES_MAX = 604_800;
    public static final long SECONDES_ROLES_PRIVILEGIES_PAR_DEFAUT = 7_200;
    public static final long SECONDES_AUTRES_ROLES_PAR_DEFAUT = 43_200;

    public PolitiqueSession {
        verifierDuree(PARAMETRE_ROLES_PRIVILEGIES, inactiviteRolesPrivilegies);
        verifierDuree(PARAMETRE_AUTRES_ROLES, inactiviteAutresRoles);
        verifierOrdre(inactiviteRolesPrivilegies, inactiviteAutresRoles);
    }

    public static PolitiqueSession parDefaut() {
        return new PolitiqueSession(Duration.ofSeconds(SECONDES_ROLES_PRIVILEGIES_PAR_DEFAUT),
                Duration.ofSeconds(SECONDES_AUTRES_ROLES_PAR_DEFAUT));
    }

    /** Délai d'inactivité d'une session ouverte par un compte de ce rôle. */
    public Duration delaiInactivite(Role role) {
        return switch (role) {
            case ADMIN_MASTER, ADMIN -> inactiviteRolesPrivilegies;
            case BENEVOLE, COUREUR -> inactiviteAutresRoles;
        };
    }

    private static void verifierDuree(String parametre, Duration duree) {
        if (duree == null) {
            throw new PolitiqueSessionInvalideException(parametre + " est obligatoire.", List.of(parametre));
        }
        if (duree.getNano() != 0 || duree.getSeconds() < SECONDES_MIN || duree.getSeconds() > SECONDES_MAX) {
            throw new PolitiqueSessionInvalideException(parametre + " doit être un nombre entier de secondes de "
                    + SECONDES_MIN + " à " + SECONDES_MAX + " (reçu : " + duree + ").", List.of(parametre));
        }
    }

    private static void verifierOrdre(Duration rolesPrivilegies, Duration autresRoles) {
        if (rolesPrivilegies.compareTo(autresRoles) > 0) {
            throw new PolitiqueSessionInvalideException(PARAMETRE_ROLES_PRIVILEGIES + " (" + rolesPrivilegies.toSeconds()
                    + " s) ne doit pas dépasser " + PARAMETRE_AUTRES_ROLES + " (" + autresRoles.toSeconds() + " s).",
                    List.of(PARAMETRE_ROLES_PRIVILEGIES, PARAMETRE_AUTRES_ROLES));
        }
    }
}
