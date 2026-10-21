package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import java.util.UUID;

/**
 * Course vue sur l'accueil public : liste fermée (identifiant, nom, date aaaa-mm-jj, adresse versionnée du logo ou
 * null). Ni statut (la rubrique le porte), ni paramètres, ni bénévoles, ni Inscriptions.
 */
public record CoursePubliqueReponse(UUID id, String nom, String date, String logoUrl) {

    @Override
    public String toString() {
        return "CoursePubliqueReponse[id=" + id + "]";
    }

    static CoursePubliqueReponse depuis(CourseListee courseListee) {
        CourseReponse course = CourseReponse.depuis(courseListee);
        return new CoursePubliqueReponse(course.id(), course.nom(), course.date(), course.logoUrl());
    }
}
