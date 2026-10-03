package fr.backyard.tracker.courses.domaine;

/** Statut d'une Course. Seul EN_PREPARATION est atteignable tant qu'une Course ne peut pas démarrer. */
public enum StatutCourse {
    EN_PREPARATION,
    EN_COURS,
    TERMINEE
}
