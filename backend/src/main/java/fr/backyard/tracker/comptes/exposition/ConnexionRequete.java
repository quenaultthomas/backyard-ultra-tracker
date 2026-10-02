package fr.backyard.tracker.comptes.exposition;

/** Corps de POST /api/connexion. Tout autre champ est ignoré ; le mot de passe n'est jamais affiché. */
public record ConnexionRequete(String pseudo, String motDePasse) {

    @Override
    public String toString() {
        return "ConnexionRequete[pseudo=" + pseudo + ", motDePasse=masqué]";
    }
}
