package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Suppression d'un bénévole refusée : il est affecté à au moins une Course EN_COURS. Porte, dans l'ordre reçu, les
 * Courses bloquantes (identifiant et nom) ; ne reçoit jamais le Compte, donc ni pseudo ni identifiant de Compte.
 */
public class BenevoleAffecteCourseEnCoursException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Course EN_COURS qui empêche la suppression, vue depuis le contexte comptes. */
    public record CourseEnCours(UUID id, String nom) {
    }

    private final transient List<CourseEnCours> courses;

    public BenevoleAffecteCourseEnCoursException(List<CourseEnCours> courses) {
        super(detail(courses));
        this.courses = List.copyOf(courses);
    }

    public List<CourseEnCours> courses() {
        return courses;
    }

    private static String detail(List<CourseEnCours> courses) {
        String noms = courses.stream().map(course -> "« " + course.nom() + " »").collect(Collectors.joining(", "));
        if (courses.size() == 1) {
            return "Ce bénévole est affecté à la course en cours " + noms
                    + ". Retirez-le de cette course avant de le supprimer.";
        }
        return "Ce bénévole est affecté aux courses en cours " + noms
                + ". Retirez-le de ces courses avant de le supprimer.";
    }
}
