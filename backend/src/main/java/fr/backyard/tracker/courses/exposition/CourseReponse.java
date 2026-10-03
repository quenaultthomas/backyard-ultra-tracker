package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Représentation d'une Course dans l'API d'administration (date au format aaaa-mm-jj). {@code logoUrl} est l'adresse
 * publique du logo, versionnée par le début de son empreinte (le serveur ignore {@code v}), ou null sans logo.
 */
public record CourseReponse(UUID id, String nom, String date, StatutCourse statut, int distanceBoucleMetres,
                            int dureeBoucleMinutes, int denivelePositifBoucleMetres, int nombreMaxParticipants,
                            int nombreMaxBoucles, String logoUrl) {

    private static final int LONGUEUR_VERSION_LOGO = 12;

    /** Identifiant uniquement : le journal (DEBUG de Spring compris) ne reprend pas le contenu de la Course. */
    @Override
    public String toString() {
        return "CourseReponse[id=" + id + "]";
    }

    static CourseReponse depuis(ListerCourses.CourseListee courseListee) {
        return depuis(courseListee.course(), courseListee.empreinteLogo());
    }

    static CourseReponse depuis(Course course, Optional<String> empreinteLogo) {
        ParametresBoucle boucle = course.parametresBoucle();
        return new CourseReponse(course.id(), course.nom(), DateTimeFormatter.ISO_LOCAL_DATE.format(course.date()),
                course.statut(), boucle.distanceMetres(), boucle.dureeMinutes(), boucle.denivelePositifMetres(),
                course.nombreMaxParticipants(), course.nombreMaxBoucles(),
                empreinteLogo.map(empreinte -> adresseLogo(course.id(), empreinte)).orElse(null));
    }

    private static String adresseLogo(UUID idCourse, String empreinte) {
        return LogoPublicController.adresse(idCourse) + "?v=" + empreinte.substring(0, LONGUEUR_VERSION_LOGO);
    }
}
