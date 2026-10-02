package fr.backyard.tracker.comptes.exposition;

/** Corps de POST /api/administration/admins. Tout autre champ (dont un éventuel role) est ignoré. */
public record CreerAdminRequete(String pseudo, String motDePasse) {

    @Override
    public String toString() {
        return "CreerAdminRequete[pseudo=" + pseudo + ", motDePasse=masqué]";
    }
}
