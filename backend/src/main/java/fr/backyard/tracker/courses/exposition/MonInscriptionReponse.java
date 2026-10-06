package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;

/**
 * Inscription vue par son coureur dans « Mes inscriptions », avec sa Course et le jeton QR : seul DTO qui transporte
 * le jeton. Jamais d'identifiant ni de pseudo de Compte. {@code toString()} ne montre que l'identifiant.
 */
public record MonInscriptionReponse(UUID id, UUID courseId, String courseNom, String courseDate,
                                    StatutCourse courseStatut, String logoUrl, int dossard,
                                    StatutInscription statut, String jetonQr) {

    @Override
    public String toString() {
        return "MonInscriptionReponse[id=" + id + "]";
    }

    static MonInscriptionReponse depuis(CourseListee courseListee, Inscription inscription) {
        CourseReponse course = CourseReponse.depuis(courseListee);
        return new MonInscriptionReponse(inscription.id(), course.id(), course.nom(), course.date(), course.statut(),
                course.logoUrl(), inscription.dossard(), inscription.statut(), inscription.jetonQr().valeur());
    }
}
