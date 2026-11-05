package fr.backyard.tracker.comptes.domaine;

import java.util.List;

/** Changement de rôle refusé : le coureur est inscrit à au moins une Course EN_COURS (9.6b RG13, refus 1). */
public final class CoureurInscritCourseEnCoursException extends ChangementRoleRefuseException {

    private static final long serialVersionUID = 1L;

    private static final Formulation FORMULATION = new Formulation(
            "Ce coureur participe à la course en cours ", "Ce coureur participe aux courses en cours ",
            ". Réessayez à la fin de la course.", ". Réessayez à la fin des courses.");

    public CoureurInscritCourseEnCoursException(List<CourseNonTerminee> courses) {
        super(courses, FORMULATION);
    }

    @Override
    public String motif() {
        return "COUREUR_INSCRIT_COURSE_EN_COURS";
    }
}
