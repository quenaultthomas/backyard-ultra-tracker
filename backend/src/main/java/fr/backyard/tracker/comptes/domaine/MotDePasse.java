package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Optional;

/**
 * Mot de passe en clair, transitoire : il n'est jamais conservé dans un compte et
 * sa valeur n'apparaît jamais dans {@link #toString()}. Longueur comptée en points de code,
 * sans suppression d'espaces, aucune règle de composition.
 */
public record MotDePasse(String valeur) {

    public static final int LONGUEUR_MIN = 12;
    /** Borne le coût du hachage sur un endpoint public. */
    public static final int LONGUEUR_MAX = 128;

    private static final String CHAMP = "motDePasse";

    private static final ViolationValidation REQUIS = new ViolationValidation(
            CHAMP, "MOT_DE_PASSE_REQUIS", "Le mot de passe est obligatoire.");
    private static final ViolationValidation TROP_COURT = new ViolationValidation(
            CHAMP, "MOT_DE_PASSE_TROP_COURT", "Le mot de passe doit faire au moins 12 caractères.");
    private static final ViolationValidation TROP_LONG = new ViolationValidation(
            CHAMP, "MOT_DE_PASSE_TROP_LONG", "Le mot de passe ne doit pas dépasser 128 caractères.");

    public MotDePasse {
        Optional<ViolationValidation> violation = verifier(valeur);
        if (violation.isPresent()) {
            throw new DonneesCompteInvalidesException(List.of(violation.get()));
        }
    }

    /** Contrôle une saisie sans construire le mot de passe, pour cumuler les violations de plusieurs champs. */
    public static Optional<ViolationValidation> verifier(String saisie) {
        if (saisie == null || saisie.isEmpty()) {
            return Optional.of(REQUIS);
        }
        int longueur = saisie.codePointCount(0, saisie.length());
        if (longueur < LONGUEUR_MIN) {
            return Optional.of(TROP_COURT);
        }
        if (longueur > LONGUEUR_MAX) {
            return Optional.of(TROP_LONG);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "MotDePasse[masqué]";
    }
}
