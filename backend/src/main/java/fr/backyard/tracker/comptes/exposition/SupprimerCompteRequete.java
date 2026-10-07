package fr.backyard.tracker.comptes.exposition;

/**
 * Corps de POST /api/comptes/moi/suppression. Tout autre champ (dont un éventuel id ou pseudo) est ignoré : le
 * compte est celui de la session. Le mot de passe n'est jamais affiché.
 */
public record SupprimerCompteRequete(String motDePasseActuel) {

    @Override
    public String toString() {
        return "SupprimerCompteRequete[motDePasseActuel=masqué]";
    }
}
