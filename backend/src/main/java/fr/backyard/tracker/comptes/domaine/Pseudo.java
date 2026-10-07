package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pseudo d'un compte, conservé tel que saisi après suppression des espaces de début et de fin.
 * Contrôles dans l'ordre, une seule violation retenue : requis, longueur, caractères.
 *
 * <p>Value object immuable écrit en classe (et non en record) : un record impose un constructeur canonique public,
 * alors que la reconstitution d'un pseudo déjà enregistré, dont le libellé « Coureur anonyme » interdit à la saisie,
 * doit contourner le contrôle de saisie (3.6 RG12) sans offrir ce contournement à la création.
 */
public final class Pseudo {

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

    /** Libellé affiché d'un Compte anonymisé ; l'espace le rend impossible à saisir. */
    private static final Pseudo ANONYME = new Pseudo(false, "Coureur anonyme");

    private final String valeur;

    /** Pseudo saisi, contrôlé. */
    public Pseudo(String valeur) {
        this(true, valeur);
    }

    private Pseudo(boolean controleDeSaisie, String valeur) {
        this.valeur = controleDeSaisie ? valeurControlee(valeur) : Objects.requireNonNull(valeur);
    }

    /** Pseudo affiché de tout Compte anonymisé (3.6 RG6). */
    public static Pseudo anonyme() {
        return ANONYME;
    }

    /**
     * Pseudo déjà enregistré, relu sans contrôle de caractères : la persistance ne contient que des pseudos acceptés à
     * la saisie ou le libellé {@link #anonyme()}.
     */
    public static Pseudo reconstituer(String valeurEnregistree) {
        return new Pseudo(false, valeurEnregistree);
    }

    public String valeur() {
        return valeur;
    }

    /** Vrai pour le libellé d'un Compte anonymisé. */
    public boolean estAnonyme() {
        return ANONYME.valeur.equals(valeur);
    }

    private static String valeurControlee(String saisie) {
        Optional<ViolationValidation> violation = verifier(saisie);
        if (violation.isPresent()) {
            throw new DonneesCompteInvalidesException(List.of(violation.get()));
        }
        return saisie.trim();
    }

    /** Contrôle une saisie sans construire le pseudo, pour cumuler les violations de plusieurs champs. */
    public static Optional<ViolationValidation> verifier(String saisie) {
        Optional<ViolationValidation> absence = verifierPresence(saisie);
        if (absence.isPresent()) {
            return absence;
        }
        String pseudo = saisie.trim();
        if (longueur(pseudo) < LONGUEUR_MIN || excedeLongueurMax(pseudo)) {
            return Optional.of(LONGUEUR);
        }
        if (!CARACTERES_AUTORISES.matcher(pseudo).matches()) {
            return Optional.of(CARACTERES);
        }
        return Optional.empty();
    }

    /** Seul contrôle de saisie appliqué à la connexion : un pseudo absent ou fait d'espaces. */
    public static Optional<ViolationValidation> verifierPresence(String saisie) {
        return saisie == null || saisie.trim().isEmpty() ? Optional.of(REQUIS) : Optional.empty();
    }

    /** Plus de {@value #LONGUEUR_MAX} caractères (points de code) après suppression des espaces de bord. */
    public static boolean excedeLongueurMax(String saisie) {
        return longueur(saisie.trim()) > LONGUEUR_MAX;
    }

    /** Clé d'identification d'une saisie (connexion) : espaces de bord supprimés, minuscules. */
    public static String normaliser(String saisie) {
        return saisie.trim().toLowerCase(Locale.ROOT);
    }

    /** Clé d'unicité insensible à la casse. */
    public String normalise() {
        return normaliser(valeur);
    }

    private static int longueur(String texte) {
        return texte.codePointCount(0, texte.length());
    }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof Pseudo pseudo && valeur.equals(pseudo.valeur);
    }

    @Override
    public int hashCode() {
        return valeur.hashCode();
    }

    @Override
    public String toString() {
        return "Pseudo[valeur=" + valeur + "]";
    }
}
