package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ListerInscritsCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.List;
import java.util.UUID;

/** Inscrits d'une Course pour l'administration : ni jeton QR, ni identifiant de Compte. */
public record InscritsCourseReponse(UUID courseId, int nombreMaxParticipants, int nombreInscrits,
                                    int placesRestantes, boolean complete, List<InscritReponse> inscrits) {

    /** Ligne d'un inscrit. */
    public record InscritReponse(UUID inscriptionId, int dossard, String pseudo, StatutInscription statut) {

        /** Identifiant et dossard uniquement : jamais de pseudo dans le journal. */
        @Override
        public String toString() {
            return "InscritReponse[inscriptionId=" + inscriptionId + ", dossard=" + dossard + "]";
        }
    }

    /** Identifiant de la Course et nombre d'inscrits uniquement : jamais de pseudo dans le journal. */
    @Override
    public String toString() {
        return "InscritsCourseReponse[courseId=" + courseId + ", nombreInscrits=" + nombreInscrits + "]";
    }

    static InscritsCourseReponse depuis(ListerInscritsCourse.InscritsCourse inscritsCourse) {
        List<InscritReponse> inscrits = inscritsCourse.inscrits().stream()
                .map(i -> new InscritReponse(i.inscriptionId(), i.dossard(), i.pseudo(), i.statut())).toList();
        return new InscritsCourseReponse(inscritsCourse.course().id(),
                inscritsCourse.course().nombreMaxParticipants(), inscritsCourse.nombreInscrits(),
                inscritsCourse.placesRestantes(), inscritsCourse.complete(), inscrits);
    }
}
