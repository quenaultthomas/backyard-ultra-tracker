package fr.backyard.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Règles du pseudo d'un compte coureur (RG2 inc. 5), définies ici seulement.
 * <ul>
 *   <li>format de la saisie : espaces de bord retirés, puis 3 à 30 caractères parmi {@code A-Z a-z 0-9 _ -} ;</li>
 *   <li>normalisation : espaces de bord retirés, puis mise en minuscules ({@link Locale#ROOT}).</li>
 * </ul>
 * Le pseudo est stocké normalisé ; toute création, authentification ou recherche passe par {@link #normalize(String)}.
 * Aucun autre code ne met un pseudo en minuscules ni ne compare des pseudos sans tenir compte de la casse.
 */
public final class Pseudo {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    /** Description de la règle de format, pour les messages d'erreur (constante de compilation). */
    public static final String FORMAT_RULE = MIN_LENGTH + " à " + MAX_LENGTH
        + " caractères parmi lettres non accentuées, chiffres, _ et -, sans espace";

    private static final Pattern INPUT_FORMAT =
        Pattern.compile("^[A-Za-z0-9_-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$");

    private Pseudo() {
    }

    /** Vrai si la saisie, privée de ses espaces de bord, respecte le format (RG2). Faux pour {@code null}. */
    public static boolean isWellFormed(String input) {
        return input != null && INPUT_FORMAT.matcher(input.trim()).matches();
    }

    /**
     * Unique normalisation d'un pseudo : espaces de bord retirés, puis minuscules. Fonction pure et idempotente.
     *
     * @throws IllegalArgumentException si la valeur est {@code null}
     */
    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Pseudo absent : normalisation impossible");
        }
        return input.trim().toLowerCase(Locale.ROOT);
    }
}
