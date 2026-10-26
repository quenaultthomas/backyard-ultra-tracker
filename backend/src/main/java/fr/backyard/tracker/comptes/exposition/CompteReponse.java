package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.Compte;
import java.time.Instant;
import java.util.UUID;

/**
 * Compte renvoyé par l'API : jamais de mot de passe, d'empreinte ni de pseudo normalisé. {@code actif} n'est faux que
 * dans les listes d'administration (un Compte désactivé ne se connecte pas).
 */
public record CompteReponse(UUID id, String pseudo, String role, Instant creeLe, boolean actif) {

    static CompteReponse depuis(Compte compte) {
        return new CompteReponse(compte.id(), compte.pseudo().valeur(), compte.role().name(), compte.creeLe(),
                compte.actif());
    }
}
