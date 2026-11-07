package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Arrays;

/**
 * Photo de profil reçue : octets d'une image PNG, JPEG ou WebP d'au plus 2 Mo, format reconnu par signature
 * ({@link FormatImage}). Toute Photo en mémoire est valide. Les octets sont copiés à l'entrée et à la lecture, et
 * n'apparaissent jamais dans {@link #toString()}.
 */
public record Photo(byte[] octets) {

    /**
     * Contrôles dans l'ordre : présence, taille, puis format (la taille prime sur le format).
     *
     * @throws PhotoInvalideException motif REQUISE, TROP_VOLUMINEUSE ou FORMAT_INVALIDE
     */
    public Photo {
        if (octets == null || octets.length == 0) {
            throw new PhotoInvalideException(PhotoInvalideException.Motif.REQUISE);
        }
        if (octets.length > FormatImage.TAILLE_MAXIMALE_OCTETS) {
            throw new PhotoInvalideException(PhotoInvalideException.Motif.TROP_VOLUMINEUSE);
        }
        if (FormatImage.detecter(octets).isEmpty()) {
            throw new PhotoInvalideException(PhotoInvalideException.Motif.FORMAT_INVALIDE);
        }
        octets = octets.clone();
    }

    /** @throws PhotoInvalideException motif REQUISE, TROP_VOLUMINEUSE ou FORMAT_INVALIDE */
    public static Photo depuis(byte[] octets) {
        return new Photo(octets);
    }

    /** Copie des octets : la modifier n'altère pas cette Photo. */
    @Override
    public byte[] octets() {
        return octets.clone();
    }

    public FormatImage format() {
        return FormatImage.detecter(octets).orElseThrow();
    }

    public String typeMime() {
        return format().typeMime();
    }

    public String extension() {
        return format().extension();
    }

    public int taille() {
        return octets.length;
    }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof Photo photo && Arrays.equals(octets, photo.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    /** Format et taille seulement, jamais les octets. */
    @Override
    public String toString() {
        return "Photo[format=" + format() + ", taille=" + taille() + " octets]";
    }
}
