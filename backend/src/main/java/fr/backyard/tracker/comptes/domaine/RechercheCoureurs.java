package fr.backyard.tracker.comptes.domaine;

import java.util.List;

/**
 * Fragment de pseudo recherché parmi les coureurs (9.5). Contrôles sur la saisie rognée, mesurée en points de code,
 * une seule violation retenue dans l'ordre : requis, trop courte ({@value Pseudo#LONGUEUR_MIN}), trop longue
 * ({@value Pseudo#LONGUEUR_MAX}). Le fragment est normalisé comme un pseudo ({@link Pseudo#normaliser}) et reste un
 * texte littéral : un fragment hors de l'alphabet des pseudos ne peut correspondre à aucun pseudo.
 */
public final class RechercheCoureurs {

    private static final String CHAMP = "q";

    private static final ViolationValidation REQUISE = new ViolationValidation(
            CHAMP, "RECHERCHE_REQUISE", "La recherche est obligatoire.");
    private static final ViolationValidation TROP_COURTE = new ViolationValidation(
            CHAMP, "RECHERCHE_TROP_COURTE", "La recherche doit contenir au moins 3 caractères.");
    private static final ViolationValidation TROP_LONGUE = new ViolationValidation(
            CHAMP, "RECHERCHE_TROP_LONGUE", "La recherche ne peut pas dépasser 30 caractères.");

    private final String fragment;
    private final boolean aucunPseudoPossible;

    private RechercheCoureurs(String saisieRognee) {
        this.fragment = Pseudo.normaliser(saisieRognee);
        this.aucunPseudoPossible = !Pseudo.respecteAlphabet(saisieRognee);
    }

    /** @throws DonneesCompteInvalidesException une seule violation, de champ « q », sans reprendre la saisie */
    public static RechercheCoureurs de(String saisie) {
        if (saisie == null) {
            throw refus(REQUISE);
        }
        String rognee = saisie.trim();
        int longueur = Pseudo.longueur(rognee);
        if (longueur < Pseudo.LONGUEUR_MIN) {
            throw refus(TROP_COURTE);
        }
        if (longueur > Pseudo.LONGUEUR_MAX) {
            throw refus(TROP_LONGUE);
        }
        return new RechercheCoureurs(rognee);
    }

    private static DonneesCompteInvalidesException refus(ViolationValidation violation) {
        return new DonneesCompteInvalidesException(List.of(violation));
    }

    /** Fragment rogné et en minuscules, à chercher littéralement dans les pseudos normalisés. */
    public String fragment() {
        return fragment;
    }

    /** Vrai si le fragment contient un caractère qu'aucun pseudo ne peut contenir : inutile de chercher. */
    public boolean aucunPseudoPossible() {
        return aucunPseudoPossible;
    }
}
