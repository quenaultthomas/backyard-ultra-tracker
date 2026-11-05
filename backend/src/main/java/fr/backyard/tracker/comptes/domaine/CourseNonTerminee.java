package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Course non TERMINEE à laquelle un Compte est inscrit, vue depuis le contexte comptes (9.6b RG20) : identifiant, nom
 * et statut, sans type du contexte courses. Ne porte jamais le Compte.
 */
public record CourseNonTerminee(UUID id, String nom, Statut statut) {

    /** Statuts d'une Course qui n'est pas TERMINEE. */
    public enum Statut {
        EN_PREPARATION,
        EN_COURS
    }

    public CourseNonTerminee {
        Objects.requireNonNull(id);
        Objects.requireNonNull(nom);
        Objects.requireNonNull(statut);
    }

    /**
     * Unique règle de refus d'un changement de rôle selon les Inscriptions (9.6b RG13) : une Course EN_COURS refuse en
     * priorité, en ne citant que les Courses EN_COURS ; sinon une Course EN_PREPARATION refuse en ne citant que
     * celles-ci ; sans Course non TERMINEE, rien n'est refusé.
     *
     * @throws CoureurInscritCourseEnCoursException       au moins une Course EN_COURS
     * @throws CoureurInscritCourseEnPreparationException au moins une Course EN_PREPARATION, aucune EN_COURS
     */
    public static void refuserSiInscrit(List<CourseNonTerminee> courses) {
        List<CourseNonTerminee> enCours = deStatut(courses, Statut.EN_COURS);
        if (!enCours.isEmpty()) {
            throw new CoureurInscritCourseEnCoursException(enCours);
        }
        List<CourseNonTerminee> enPreparation = deStatut(courses, Statut.EN_PREPARATION);
        if (!enPreparation.isEmpty()) {
            throw new CoureurInscritCourseEnPreparationException(enPreparation);
        }
    }

    private static List<CourseNonTerminee> deStatut(List<CourseNonTerminee> courses, Statut statut) {
        return courses.stream().filter(course -> course.statut() == statut).toList();
    }
}
