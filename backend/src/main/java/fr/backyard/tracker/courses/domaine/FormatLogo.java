package fr.backyard.tracker.courses.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.Arrays;
import java.util.Optional;

/**
 * Formats de logo autorisés : ceux de {@link FormatImage}, qui seul définit les signatures (10.1a RG15a). Reconnus
 * uniquement par les premiers octets, jamais par un nom de fichier ni un type déclaré.
 */
public enum FormatLogo {

    PNG(FormatImage.PNG),
    JPEG(FormatImage.JPEG),
    WEBP(FormatImage.WEBP);

    private final FormatImage image;

    FormatLogo(FormatImage image) {
        this.image = image;
    }

    public String typeMime() {
        return image.typeMime();
    }

    /** Format dont la signature ouvre ces octets ; vide si aucun (y compris un contenu plus court que la signature). */
    static Optional<FormatLogo> reconnaitre(byte[] octets) {
        return FormatImage.detecter(octets).map(FormatLogo::depuis);
    }

    private static FormatLogo depuis(FormatImage image) {
        return Arrays.stream(values()).filter(format -> format.image == image).findFirst().orElseThrow();
    }
}
