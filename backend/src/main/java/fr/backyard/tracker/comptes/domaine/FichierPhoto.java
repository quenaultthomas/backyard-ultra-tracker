package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;
import java.util.Objects;

/**
 * Entrée directe du répertoire des photos, telle que vue par le nettoyage (correctif audit jalon 10 RG4) : son nom,
 * sa date de dernière modification et sa nature ({@code ordinaire} faux pour un sous-répertoire, un lien symbolique ou
 * toute entrée qui n'est pas un fichier régulier).
 */
public record FichierPhoto(String nom, Instant modifieLe, boolean ordinaire) {

    public FichierPhoto {
        Objects.requireNonNull(nom);
        Objects.requireNonNull(modifieLe);
    }

    /** Nom masqué : celui d'une photo contient son jeton. */
    @Override
    public String toString() {
        return "FichierPhoto[ordinaire=" + ordinaire + "]";
    }
}
