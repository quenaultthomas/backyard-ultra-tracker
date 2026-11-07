package fr.backyard.tracker.partage.domaine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Formats d'image acceptés (logo de Course, photo de profil), reconnus uniquement par la signature des premiers octets
 * (jamais par un nom de fichier ni un type déclaré). Seul endroit où sont définies les signatures et la taille maximale
 * d'une image envoyée.
 */
public enum FormatImage {

    PNG("image/png", "png", Signature.au(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
    JPEG("image/jpeg", "jpg", Signature.au(0, 0xFF, 0xD8, 0xFF)),
    /** « RIFF », 4 octets de taille quelconques, puis « WEBP ». */
    WEBP("image/webp", "webp", new Signature(0, ascii("RIFF")), new Signature(8, ascii("WEBP")));

    /** 2 Mo = 2 × 1024 × 1024 octets : seul endroit de cette limite. */
    public static final int TAILLE_MAXIMALE_OCTETS = 2 * 1024 * 1024;

    private final String typeMime;
    private final String extension;
    private final Signature[] signatures;

    FormatImage(String typeMime, String extension, Signature... signatures) {
        this.typeMime = typeMime;
        this.extension = extension;
        this.signatures = signatures;
    }

    /** Type MIME servi pour ce format, jamais celui déclaré par l'envoyeur. */
    public String typeMime() {
        return typeMime;
    }

    /** Extension de fichier, sans point. */
    public String extension() {
        return extension;
    }

    /** Format dont la signature ouvre ces octets ; vide si aucun (octets absents ou plus courts que la signature). */
    public static Optional<FormatImage> detecter(byte[] octets) {
        if (octets == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(format -> format.ouvre(octets)).findFirst();
    }

    private boolean ouvre(byte[] octets) {
        return Arrays.stream(signatures).allMatch(signature -> signature.presenteDans(octets));
    }

    private static byte[] ascii(String texte) {
        return texte.getBytes(StandardCharsets.US_ASCII);
    }

    /** Suite d'octets attendue à une position donnée. */
    private record Signature(int position, byte[] octets) {

        static Signature au(int position, int... octets) {
            byte[] valeurs = new byte[octets.length];
            for (int i = 0; i < octets.length; i++) {
                valeurs[i] = (byte) octets[i];
            }
            return new Signature(position, valeurs);
        }

        boolean presenteDans(byte[] contenu) {
            int fin = position + octets.length;
            return contenu.length >= fin && Arrays.equals(contenu, position, fin, octets, 0, octets.length);
        }
    }
}
