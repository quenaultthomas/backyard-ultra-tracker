package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.partage.domaine.AdressePhoto;

/** Photo enregistrée : son adresse publique relative, jamais nulle ; ni identifiant de Compte ni pseudo. */
public record PhotoReponse(String photoUrl) {

    static PhotoReponse depuis(String jeton) {
        return new PhotoReponse(AdressePhoto.de(jeton));
    }
}
