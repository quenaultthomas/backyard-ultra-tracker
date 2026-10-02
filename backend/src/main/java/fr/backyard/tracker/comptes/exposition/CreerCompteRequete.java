package fr.backyard.tracker.comptes.exposition;

/** Corps de POST /api/comptes. Tout autre champ (dont un éventuel role) est ignoré. */
public record CreerCompteRequete(String pseudo, String motDePasse) {

    @Override
    public String toString() {
        return "CreerCompteRequete[pseudo=" + pseudo + ", motDePasse=masqué]";
    }
}
