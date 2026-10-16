package fr.backyard.tracker.courses.domaine;

/** Motif de l'abandon d'une Inscription. Seul HORS_DELAI est produit par l'abandon automatique. */
public enum MotifAbandon {
    VOLONTAIRE,
    HORS_DELAI,
    MANUEL,
    AUTRE;

    /**
     * Un admin choisit tout motif sauf HORS_DELAI, réservé à l'abandon automatique. Unique définition de cette règle.
     */
    public boolean estPermisPourAbandonManuel() {
        return this != HORS_DELAI;
    }
}
