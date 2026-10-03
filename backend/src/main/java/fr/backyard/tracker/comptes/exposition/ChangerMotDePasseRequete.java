package fr.backyard.tracker.comptes.exposition;

/**
 * Corps de PUT /api/comptes/moi/mot-de-passe. Tout autre champ (dont un éventuel id ou pseudo) est
 * ignoré : le compte est celui de la session. Les mots de passe ne sont jamais affichés.
 */
public record ChangerMotDePasseRequete(String motDePasseActuel, String nouveauMotDePasse) {

    @Override
    public String toString() {
        return "ChangerMotDePasseRequete[motDePasseActuel=masqué, nouveauMotDePasse=masqué]";
    }
}
