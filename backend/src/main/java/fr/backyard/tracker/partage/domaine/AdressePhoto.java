package fr.backyard.tracker.partage.domaine;

/**
 * Adresse publique relative d'une photo de profil (10.1 RG11, 10.2 RG1) : seul endroit où est défini le préfixe
 * {@code /api/photos/}, partagé par le contexte comptes (lecture publique, Mon compte) et le contexte courses (suivi).
 */
public final class AdressePhoto {

    /** Route publique des photos, sans barre finale (correspondance de chemins : sécurité, limite de débit). */
    public static final String ROUTE = "/api/photos";

    /** Préfixe de l'adresse publique, suivi du jeton opaque de la photo. */
    public static final String PREFIXE = ROUTE + "/";

    private AdressePhoto() {
    }

    /** Adresse relative de la photo de ce jeton, jamais absolue. */
    public static String de(String jeton) {
        return PREFIXE + jeton;
    }
}
