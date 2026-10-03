package fr.backyard.tracker.courses.domaine;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Jeton opaque lu par le scan du QR code d'une Inscription : 32 octets aléatoires ({@link SecureRandom}) encodés en
 * base64url sans remplissage (43 caractères), sans lien calculable avec le dossard. C'est un secret de lecture : il
 * n'apparaît jamais dans {@link #toString()}, donc ni dans les journaux ni dans les messages d'erreur.
 */
public record JetonQr(String valeur) {

    private static final int NOMBRE_OCTETS = 32;
    private static final SecureRandom ALEA = new SecureRandom();
    private static final Base64.Encoder ENCODAGE = Base64.getUrlEncoder().withoutPadding();

    public JetonQr {
        Objects.requireNonNull(valeur, "Le jeton QR est obligatoire");
        if (valeur.isBlank()) {
            throw new IllegalArgumentException("Le jeton QR ne peut pas être vide");
        }
    }

    /** Nouveau jeton aléatoire, non devinable. */
    public static JetonQr generer() {
        byte[] octets = new byte[NOMBRE_OCTETS];
        ALEA.nextBytes(octets);
        return new JetonQr(ENCODAGE.encodeToString(octets));
    }

    @Override
    public String toString() {
        return "JetonQr[masqué]";
    }
}
