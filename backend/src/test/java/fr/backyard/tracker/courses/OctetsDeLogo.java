package fr.backyard.tracker.courses;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Fabrique d'octets de test pour les logos (incrément 2.3) : signatures réelles, construites en code. */
public final class OctetsDeLogo {

    public static final int TAILLE_MAXIMALE = 2_097_152;

    private static final byte[] SIGNATURE_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] SIGNATURE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private OctetsDeLogo() {
    }

    /** Signature PNG suivie de {@code contenu}. */
    public static byte[] png(byte[] contenu) {
        return concatener(SIGNATURE_PNG, contenu);
    }

    /** PNG (signature + 100 octets de motif). */
    public static byte[] png() {
        return png(motif(100, 1));
    }

    /** PNG d'une taille totale exacte (signature + remplissage de zéros). */
    public static byte[] pngDeTaille(int tailleTotale) {
        return png(new byte[tailleTotale - SIGNATURE_PNG.length]);
    }

    /** JPEG : {@code FF D8 FF E0} + 100 octets de motif. */
    public static byte[] jpeg() {
        return concatener(SIGNATURE_JPEG, new byte[] {(byte) 0xE0}, motif(100, 2));
    }

    /** WebP : {@code RIFF}, 4 octets quelconques, {@code WEBP}, 100 octets de motif. */
    public static byte[] webp() {
        return concatener(ascii("RIFF"), new byte[] {0x24, 0x00, 0x00, 0x00}, ascii("WEBP"), motif(100, 3));
    }

    public static byte[] ascii(String texte) {
        return texte.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] motif(int taille, int graine) {
        byte[] octets = new byte[taille];
        for (int i = 0; i < taille; i++) {
            octets[i] = (byte) (i * 7 + graine);
        }
        return octets;
    }

    public static byte[] concatener(byte[]... morceaux) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        for (byte[] morceau : morceaux) {
            sortie.writeBytes(morceau);
        }
        return sortie.toByteArray();
    }

    /** Texte rempli d'espaces jusqu'à la taille demandée (pour les cas de taille sans signature valide). */
    public static byte[] texteDeTaille(String debut, int tailleTotale) {
        byte[] octets = new byte[tailleTotale];
        Arrays.fill(octets, (byte) ' ');
        byte[] entete = ascii(debut);
        System.arraycopy(entete, 0, octets, 0, entete.length);
        return octets;
    }

    /** SHA-256 hexadécimal minuscule, calculé indépendamment de la production. */
    public static String sha256(byte[] octets) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(octets));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
