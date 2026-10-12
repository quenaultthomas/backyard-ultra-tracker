package fr.backyard.tracker.courses.domaine;

import java.util.Optional;

/**
 * Refus d'un Passage pour une Inscription connue. Le domaine le lève sans coureur ; le cas d'usage le relance
 * avec le coureur scanné ({@link #pour}), restitué au seul bénévole qui détient le jeton. Le message ne contient
 * jamais de pseudo.
 */
public abstract class RefusPassageCoureurException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient CoureurScanne coureur;

    protected RefusPassageCoureurException(String message, CoureurScanne coureur) {
        super(message);
        this.coureur = coureur;
    }

    /** Coureur concerné, vide tant que le refus n'a pas été rapporté à un coureur. */
    public Optional<CoureurScanne> coureur() {
        return Optional.ofNullable(coureur);
    }

    /** Le même refus, rapporté au coureur scanné. */
    public abstract RefusPassageCoureurException pour(CoureurScanne coureurScanne);
}
