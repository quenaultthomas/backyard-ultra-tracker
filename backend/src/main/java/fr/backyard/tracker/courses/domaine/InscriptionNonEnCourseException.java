package fr.backyard.tracker.courses.domaine;

/** Une Inscription en abandon ou vainqueur n'obtient plus de Passage. */
public class InscriptionNonEnCourseException extends RefusPassageCoureurException {

    private static final long serialVersionUID = 1L;

    private final StatutInscription statut;

    public InscriptionNonEnCourseException(StatutInscription statut) {
        this(statut, null);
    }

    public InscriptionNonEnCourseException(StatutInscription statut, CoureurScanne coureur) {
        super("Inscription non en course (statut " + statut + ")", coureur);
        this.statut = statut;
    }

    public StatutInscription statut() {
        return statut;
    }

    @Override
    public InscriptionNonEnCourseException pour(CoureurScanne coureurScanne) {
        return new InscriptionNonEnCourseException(statut, coureurScanne);
    }
}
