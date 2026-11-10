package fr.backyard.tracker.comptes.exposition;

/** Photo enregistrée : son adresse publique relative, jamais nulle ; ni identifiant de Compte ni pseudo. */
public record PhotoReponse(String photoUrl) {

    static PhotoReponse depuis(String jeton) {
        return new PhotoReponse(RoutePhotos.adresseDe(jeton));
    }
}
