package fr.backyard.tracker.courses.domaine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Logo d'une Course : octets d'une image PNG, JPEG ou WebP d'au plus 2 Mo, format reconnu par signature. Tout Logo en
 * mémoire est valide (contrôle à la construction). Les octets sont copiés à l'entrée et à la lecture, et n'apparaissent
 * jamais dans {@link #toString()}.
 */
public record Logo(byte[] octets) {

    /** 2 Mo = 2 × 1024 × 1024 octets : seul endroit de cette limite. */
    public static final int TAILLE_MAXIMALE_OCTETS = 2 * 1024 * 1024;

    /**
     * Contrôles dans l'ordre : présence, taille, puis format (la taille prime sur le format).
     *
     * @throws LogoInvalideException motif REQUIS, TROP_VOLUMINEUX ou FORMAT_INVALIDE
     */
    public Logo {
        if (octets == null || octets.length == 0) {
            throw new LogoInvalideException(LogoInvalideException.Motif.REQUIS);
        }
        if (octets.length > TAILLE_MAXIMALE_OCTETS) {
            throw new LogoInvalideException(LogoInvalideException.Motif.TROP_VOLUMINEUX);
        }
        if (FormatLogo.reconnaitre(octets).isEmpty()) {
            throw new LogoInvalideException(LogoInvalideException.Motif.FORMAT_INVALIDE);
        }
        octets = octets.clone();
    }

    /** @throws LogoInvalideException motif REQUIS, TROP_VOLUMINEUX ou FORMAT_INVALIDE */
    public static Logo depuis(byte[] octets) {
        return new Logo(octets);
    }

    /** Copie des octets : la modifier n'altère pas ce Logo. */
    @Override
    public byte[] octets() {
        return octets.clone();
    }

    public FormatLogo format() {
        return FormatLogo.reconnaitre(octets).orElseThrow();
    }

    /** Type MIME du format reconnu, jamais celui déclaré par l'envoyeur. */
    public String typeMime() {
        return format().typeMime();
    }

    public int taille() {
        return octets.length;
    }

    /** SHA-256 des octets, en hexadécimal minuscule (64 caractères). */
    public String empreinte() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(octets));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 est fourni par toute JVM", exception);
        }
    }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof Logo logo && Arrays.equals(octets, logo.octets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets);
    }

    /** Type et taille seulement : ni les octets ni l'empreinte. */
    @Override
    public String toString() {
        return "Logo[type=" + typeMime() + ", taille=" + taille() + " octets]";
    }
}
