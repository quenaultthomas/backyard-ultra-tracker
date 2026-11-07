package fr.backyard.tracker.comptes.domaine;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Jeton public d'une photo de profil : 16 octets de {@link SecureRandom} en 32 caractères hexadécimaux minuscules,
 * non devinable et sans lien avec l'identifiant du Compte. Il forme l'adresse publique et le nom du fichier.
 */
public final class JetonPhoto {

    private static final int OCTETS_ALEATOIRES = 16;
    private static final Pattern FORME = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom ALEA = new SecureRandom();

    private JetonPhoto() {
    }

    public static String generer() {
        byte[] octets = new byte[OCTETS_ALEATOIRES];
        ALEA.nextBytes(octets);
        return HexFormat.of().formatHex(octets);
    }

    /** Unique contrôle de la forme d'un jeton, avant tout accès à la base ou au disque. */
    public static boolean estValide(String jeton) {
        return jeton != null && FORME.matcher(jeton).matches();
    }
}
