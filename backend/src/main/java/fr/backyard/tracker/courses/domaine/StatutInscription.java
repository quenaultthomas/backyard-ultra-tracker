package fr.backyard.tracker.courses.domaine;

/** Statut d'une Inscription. Seul EN_COURSE est atteignable tant qu'aucune Course ne démarre. */
public enum StatutInscription {
    EN_COURSE,
    ABANDON,
    VAINQUEUR
}
