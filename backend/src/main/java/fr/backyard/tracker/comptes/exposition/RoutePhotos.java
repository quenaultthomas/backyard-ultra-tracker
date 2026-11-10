package fr.backyard.tracker.comptes.exposition;

/**
 * Route publique des photos de profil dans l'exposition de comptes (10.1 RG11, correctif audit jalon 10 RG12) : le
 * contrôleur de lecture et les réponses de Mon compte. Le suivi (exposition de courses) et la limite de débit
 * (infrastructure de courses) gardent chacun leur propre copie, sans paquet partagé ; leur cohérence est vérifiée par
 * un test d'intégration.
 */
final class RoutePhotos {

    /** Préfixe de l'adresse publique, suivi du jeton opaque de la photo. */
    static final String PREFIXE = "/api/photos/";

    private RoutePhotos() {
    }

    /** Adresse relative de la photo de ce jeton, jamais absolue. */
    static String adresseDe(String jeton) {
        return PREFIXE + jeton;
    }
}
