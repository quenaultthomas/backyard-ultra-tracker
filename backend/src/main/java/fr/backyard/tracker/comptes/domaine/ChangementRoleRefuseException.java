package fr.backyard.tracker.comptes.domaine;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Refus d'un changement de rôle : le coureur est inscrit à des Courses non TERMINEE (9.6b RG13). Porte ces Courses
 * triées par nom (insensible à la casse) puis identifiant ; ne reçoit jamais le Compte, donc ni pseudo ni identifiant
 * de Compte. Le motif est un code stable, repris tel quel par le journal (sans nom de Course).
 */
public abstract sealed class ChangementRoleRefuseException extends RuntimeException
        permits CoureurInscritCourseEnCoursException, CoureurInscritCourseEnPreparationException {

    private static final long serialVersionUID = 1L;

    private static final Comparator<CourseNonTerminee> ORDRE_DES_COURSES = Comparator
            .comparing((CourseNonTerminee course) -> course.nom().toLowerCase(Locale.ROOT))
            .thenComparing(CourseNonTerminee::id);

    private final transient List<CourseNonTerminee> courses;

    protected ChangementRoleRefuseException(List<CourseNonTerminee> courses, Formulation formulation) {
        super(formulation.detail(trier(courses)));
        this.courses = trier(courses);
    }

    /** Courses qui motivent le refus, triées par nom (insensible à la casse) puis identifiant. */
    public List<CourseNonTerminee> courses() {
        return courses;
    }

    /** Code stable du refus. */
    public abstract String motif();

    private static List<CourseNonTerminee> trier(List<CourseNonTerminee> courses) {
        return courses.stream().sorted(ORDRE_DES_COURSES).toList();
    }

    /** Phrases du détail au singulier et au pluriel, encadrant la liste des noms de Courses. */
    protected record Formulation(String debutSingulier, String debutPluriel, String finSingulier, String finPluriel) {

        String detail(List<CourseNonTerminee> courses) {
            String noms = courses.stream().map(course -> "« " + course.nom() + " »").collect(Collectors.joining(", "));
            boolean seule = courses.size() == 1;
            return (seule ? debutSingulier : debutPluriel) + noms + (seule ? finSingulier : finPluriel);
        }
    }
}
