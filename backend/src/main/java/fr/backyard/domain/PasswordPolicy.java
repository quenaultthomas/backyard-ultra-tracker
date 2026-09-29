package fr.backyard.domain;

import java.nio.charset.StandardCharsets;

/**
 * Règle du mot de passe d'un compte coureur (RG3 inc. 5), définie ici seulement : au moins 8 caractères et au
 * plus 72 octets UTF-8 (limite de BCrypt, au-delà de laquelle la fin du mot de passe serait ignorée).
 * Aucun nettoyage d'espaces.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 8;
    public static final int MAX_UTF8_BYTES = 72;

    /** Description de la règle, pour les messages d'erreur (constante de compilation). */
    public static final String RULE = MIN_CHARACTERS + " caractères minimum et " + MAX_UTF8_BYTES + " octets maximum";

    private PasswordPolicy() {
    }

    /** Vrai si le mot de passe respecte RG3. Faux pour {@code null}. */
    public static boolean isAcceptable(String password) {
        return password != null
            && password.codePointCount(0, password.length()) >= MIN_CHARACTERS
            && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
