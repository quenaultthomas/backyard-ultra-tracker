package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.Optional;
import java.util.UUID;

/**
 * Course ouverte vue par un coureur, avec sa propre Inscription ou null. Ni statut (toujours EN_PREPARATION), ni
 * bénévoles affectés, ni Inscriptions des autres coureurs.
 */
public record CourseOuverteReponse(UUID id, String nom, String date, int distanceBoucleMetres,
                                   int dureeBoucleMinutes, int denivelePositifBoucleMetres,
                                   int nombreMaxParticipants, int nombreMaxBoucles, String logoUrl,
                                   InscriptionReponse monInscription) {

    @Override
    public String toString() {
        return "CourseOuverteReponse[id=" + id + "]";
    }

    static CourseOuverteReponse depuis(CourseListee courseListee, Optional<Inscription> monInscription) {
        CourseReponse course = CourseReponse.depuis(courseListee);
        return new CourseOuverteReponse(course.id(), course.nom(), course.date(), course.distanceBoucleMetres(),
                course.dureeBoucleMinutes(), course.denivelePositifBoucleMetres(), course.nombreMaxParticipants(),
                course.nombreMaxBoucles(), course.logoUrl(),
                monInscription.map(InscriptionReponse::depuis).orElse(null));
    }
}
