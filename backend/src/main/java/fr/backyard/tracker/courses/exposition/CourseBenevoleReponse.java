package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.util.UUID;

/** Course vue par un bénévole affecté : ni paramètres de boucle ni autres bénévoles. */
public record CourseBenevoleReponse(UUID id, String nom, String date, StatutCourse statut, String logoUrl) {

    @Override
    public String toString() {
        return "CourseBenevoleReponse[id=" + id + "]";
    }

    static CourseBenevoleReponse depuis(ListerCourses.CourseListee courseListee) {
        CourseReponse course = CourseReponse.depuis(courseListee);
        return new CourseBenevoleReponse(course.id(), course.nom(), course.date(), course.statut(), course.logoUrl());
    }
}
