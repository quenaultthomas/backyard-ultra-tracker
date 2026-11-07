package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Objects;

/** Photo de profil portée par un Compte : son jeton public et son format, jamais ses octets. */
public record ReferencePhoto(String jeton, FormatImage format) {

    /** @throws IllegalArgumentException si le jeton n'a pas la forme d'un {@link JetonPhoto} */
    public ReferencePhoto {
        if (!JetonPhoto.estValide(jeton)) {
            throw new IllegalArgumentException("Jeton de photo mal formé.");
        }
        Objects.requireNonNull(format);
    }

    /** Jeton masqué : il donne accès à la photo. */
    @Override
    public String toString() {
        return "ReferencePhoto[format=" + format + "]";
    }
}
