package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.partage.domaine.AdressePhoto;
import java.time.Instant;
import java.util.UUID;

/**
 * Compte de la session ({@code GET /api/comptes/moi}) : champs de {@link CompteReponse} et adresse publique de la photo
 * de profil, nulle sans photo (10.1 RG11).
 */
public record CompteConnecteReponse(UUID id, String pseudo, String role, Instant creeLe, boolean actif,
                                    String photoUrl) {

    static CompteConnecteReponse depuis(Compte compte) {
        return new CompteConnecteReponse(compte.id(), compte.pseudo().valeur(), compte.role().name(), compte.creeLe(),
                compte.actif(), compte.photo() == null ? null : AdressePhoto.de(compte.photo().jeton()));
    }
}
