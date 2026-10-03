package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Représentation d'une Course dans l'API d'administration (date au format aaaa-mm-jj). */
public record CourseReponse(UUID id, String nom, String date, StatutCourse statut, int distanceBoucleMetres,
                            int dureeBoucleMinutes, int denivelePositifBoucleMetres, int nombreMaxParticipants,
                            int nombreMaxBoucles) {

    /** Identifiant uniquement : le journal (DEBUG de Spring compris) ne reprend pas le contenu de la Course. */
    @Override
    public String toString() {
        return "CourseReponse[id=" + id + "]";
    }

    static CourseReponse depuis(Course course) {
        ParametresBoucle boucle = course.parametresBoucle();
        return new CourseReponse(course.id(), course.nom(), DateTimeFormatter.ISO_LOCAL_DATE.format(course.date()),
                course.statut(), boucle.distanceMetres(), boucle.dureeMinutes(), boucle.denivelePositifMetres(),
                course.nombreMaxParticipants(), course.nombreMaxBoucles());
    }
}
