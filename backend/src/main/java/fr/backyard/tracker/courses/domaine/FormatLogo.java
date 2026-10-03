package fr.backyard.tracker.courses.domaine;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Formats de logo autorisés, reconnus uniquement par la signature des premiers octets (jamais par un nom de fichier ni
 * un type déclaré). Seul endroit où sont définies les signatures.
 */
public enum FormatLogo {

    PNG("image/png", Signature.au(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
    JPEG("image/jpeg", Signature.au(0, 0xFF, 0xD8, 0xFF)),
    /** « RIFF », 4 octets de taille quelconques, puis « WEBP ». */
    WEBP("image/webp", new Signature(0, ascii("RIFF")), new Signature(8, ascii("WEBP")));

    private final String typeMime;
    private final Signature[] signatures;

    FormatLogo(String typeMime, Signature... signatures) {
        this.typeMime = typeMime;
        this.signatures = signatures;
    }

    public String typeMime() {
        return typeMime;
    }

    /** Format dont la signature ouvre ces octets ; vide si aucun (y compris un contenu plus court que la signature). */
    static Optional<FormatLogo> reconnaitre(byte[] octets) {
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
