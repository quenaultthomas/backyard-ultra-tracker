package fr.backyard.tracker.courses.domaine;

/** Le temps de la dernière Boucle autorisée est écoulé : aucune Boucle n'est calculable pour le Passage. */
public class TempsDeCourseEcouleException extends RefusPassageCoureurException {

    private static final long serialVersionUID = 1L;

    public TempsDeCourseEcouleException() {
        this(null);
    }

    public TempsDeCourseEcouleException(CoureurScanne coureur) {
        super("Temps de la dernière boucle écoulé", coureur);
    }

    @Override
    public TempsDeCourseEcouleException pour(CoureurScanne coureurScanne) {
        return new TempsDeCourseEcouleException(coureurScanne);
    }
}
