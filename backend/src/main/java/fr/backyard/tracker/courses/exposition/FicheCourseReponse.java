package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fiche d'administration d'une Course : les dix champs de {@link CourseReponse}, les bénévoles affectés, triés, et
 * l'heure de départ (instant UTC à la seconde, null tant que la Course n'a pas démarré ou sans heure enregistrée).
 */
public record FicheCourseReponse(UUID id, String nom, String date, StatutCourse statut, int distanceBoucleMetres,
                                 int dureeBoucleMinutes, int denivelePositifBoucleMetres, int nombreMaxParticipants,
                                 int nombreMaxBoucles, String logoUrl, List<UUID> benevoleIds,
                                 Instant demarreeLe) {

    /** Identifiant uniquement : le journal ne reprend ni le contenu de la Course ni les bénévoles. */
    @Override
    public String toString() {
        return "FicheCourseReponse[id=" + id + "]";
    }

    static FicheCourseReponse depuis(ListerCourses.CourseListee courseListee, List<UUID> benevoleIds) {
        CourseReponse course = CourseReponse.depuis(courseListee);
        return new FicheCourseReponse(course.id(), course.nom(), course.date(), course.statut(),
                course.distanceBoucleMetres(), course.dureeBoucleMinutes(), course.denivelePositifBoucleMetres(),
                course.nombreMaxParticipants(), course.nombreMaxBoucles(), course.logoUrl(), benevoleIds,
                courseListee.course().demarreeLe().orElse(null));
    }
}
