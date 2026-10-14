package fr.backyard.tracker.courses.domaine;

/** Motif de l'abandon d'une Inscription. Seul HORS_DELAI est produit par l'abandon automatique. */
public enum MotifAbandon {
    VOLONTAIRE,
    HORS_DELAI,
    MANUEL,
    AUTRE
}
