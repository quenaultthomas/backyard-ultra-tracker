package fr.backyard.tracker.comptes.domaine;

import java.util.List;

/**
 * Changement de rôle refusé : le coureur est inscrit à au moins une Course EN_PREPARATION et à aucune Course EN_COURS
 * (9.6b RG13, refus 2). Il se lève par la désinscription du coureur.
 */
public final class CoureurInscritCourseEnPreparationException extends ChangementRoleRefuseException {

    private static final long serialVersionUID = 1L;

    private static final Formulation FORMULATION = new Formulation(
            "Ce coureur est inscrit à la course ", "Ce coureur est inscrit aux courses ",
            " qui n'a pas démarré. Il doit d'abord se désinscrire.",
            " qui n'ont pas démarré. Il doit d'abord se désinscrire.");

    public CoureurInscritCourseEnPreparationException(List<CourseNonTerminee> courses) {
        super(courses, FORMULATION);
    }

    @Override
    public String motif() {
        return "COUREUR_INSCRIT_COURSE_EN_PREPARATION";
    }
}
