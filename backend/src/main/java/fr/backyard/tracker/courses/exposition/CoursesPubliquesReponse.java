package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.application.ListerCoursesPubliques.CoursesPubliques;
import java.util.List;

/** Accueil public : Courses en cours, à venir et terminées, dans l'ordre fixé par le domaine. */
public record CoursesPubliquesReponse(List<CoursePubliqueReponse> enCours, List<CoursePubliqueReponse> aVenir,
                                      List<CoursePubliqueReponse> terminees) {

    /** Effectifs des rubriques uniquement. */
    @Override
    public String toString() {
        return "CoursesPubliquesReponse[enCours=" + enCours.size() + ", aVenir=" + aVenir.size() + ", terminees="
                + terminees.size() + "]";
    }

    static CoursesPubliquesReponse depuis(CoursesPubliques courses) {
        return new CoursesPubliquesReponse(rubrique(courses.enCours()), rubrique(courses.aVenir()),
                rubrique(courses.terminees()));
    }

    private static List<CoursePubliqueReponse> rubrique(List<CourseListee> courses) {
        return courses.stream().map(CoursePubliqueReponse::depuis).toList();
    }
}
