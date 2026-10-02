package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pseudo d'un compte, conservé tel que saisi après suppression des espaces de début et de fin.
 * Contrôles dans l'ordre, une seule violation retenue : requis, longueur, caractères.
 */
public record Pseudo(String valeur) {

    public static final int LONGUEUR_MIN = 3;
    public static final int LONGUEUR_MAX = 30;

    private static final String CHAMP = "pseudo";
    private static final Pattern CARACTERES_AUTORISES = Pattern.compile("[\\p{L}\\p{Nd}._-]+");

    private static final ViolationValidation REQUIS = new ViolationValidation(
            CHAMP, "PSEUDO_REQUIS", "Le pseudo est obligatoire.");
    private static final ViolationValidation LONGUEUR = new ViolationValidation(
            CHAMP, "PSEUDO_LONGUEUR", "Le pseudo doit faire entre 3 et 30 caractères.");
    private static final ViolationValidation CARACTERES = new ViolationValidation(
            CHAMP, "PSEUDO_CARACTERES",
            "Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ».");

    public Pseudo {
        Optional<ViolationValidation> violation = verifier(valeur);
        if (violation.isPresent()) {
            throw new DonneesCompteInvalidesException(List.of(violation.get()));
        }
        valeur = valeur.trim();
    }

    /** Contrôle une saisie sans construire le pseudo, pour cumuler les violations de plusieurs champs. */
    public static Optional<ViolationValidation> verifier(String saisie) {
        String pseudo = saisie == null ? "" : saisie.trim();
        if (pseudo.isEmpty()) {
            return Optional.of(REQUIS);
        }
        int longueur = pseudo.codePointCount(0, pseudo.length());
        if (longueur < LONGUEUR_MIN || longueur > LONGUEUR_MAX) {
            return Optional.of(LONGUEUR);
        }
        if (!CARACTERES_AUTORISES.matcher(pseudo).matches()) {
            return Optional.of(CARACTERES);
        }
        return Optional.empty();
    }

    /** Clé d'unicité insensible à la casse. */
    public String normalise() {
        return valeur.toLowerCase(Locale.ROOT);
    }
}
