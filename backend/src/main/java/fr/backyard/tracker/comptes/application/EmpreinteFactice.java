package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Empreinte d'une valeur aléatoire aussitôt oubliée, vérifiée à la place d'une empreinte absente :
 * le coût de calcul est le même que pour un compte existant, sans qu'aucune saisie puisse la satisfaire.
 */
final class EmpreinteFactice {

    private static final int OCTETS_VALEUR_FACTICE = 24;

    private EmpreinteFactice() {
    }

    static String calculer(EncodeurMotDePasse encodeurMotDePasse) {
        return encodeurMotDePasse.encoder(new MotDePasse(valeurAleatoire()));
    }

    private static String valeurAleatoire() {
        byte[] octets = new byte[OCTETS_VALEUR_FACTICE];
        new SecureRandom().nextBytes(octets);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }
}
