package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.LireDossard;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;

/**
 * Dossard imprimable vu par l'administration : avec {@link MonInscriptionReponse}, seul DTO qui transporte le jeton
 * QR. Jamais d'identifiant de Compte. {@code toString()} ne montre que l'identifiant de l'Inscription.
 */
public record DossardReponse(UUID inscriptionId, UUID courseId, String courseNom, StatutCourse courseStatut,
                             int dossard, String pseudo, StatutInscription statut, String jetonQr) {

    @Override
    public String toString() {
        return "Dossard[inscription=" + inscriptionId + "]";
    }

    static DossardReponse depuis(LireDossard.Dossard dossard) {
        var course = dossard.course();
        var inscription = dossard.inscription();
        return new DossardReponse(inscription.id(), course.id(), course.nom(), course.statut(),
                inscription.dossard(), dossard.pseudo(), inscription.statut(), inscription.jetonQr().valeur());
    }
}
