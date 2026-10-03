package fr.backyard.tracker.comptes.exposition;

/** Corps de POST /api/administration/benevoles. Tout autre champ (dont un éventuel role) est ignoré. */
public record CreerBenevoleRequete(String pseudo, String motDePasse) {

    @Override
    public String toString() {
        return "CreerBenevoleRequete[pseudo=" + pseudo + ", motDePasse=masqué]";
    }
}
