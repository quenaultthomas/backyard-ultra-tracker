package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.AbandonnerCoureur;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;

/** Réponse de l'abandon manuel : ni jeton QR, ni identifiant de Compte, ni pseudo. */
public record AbandonManuelReponse(UUID inscriptionId, int dossard, StatutInscription statut,
                                   MotifAbandon motifAbandon, int boucleAbandon, StatutCourse statutCourse) {

    static AbandonManuelReponse depuis(AbandonnerCoureur.Resultat resultat) {
        Inscription inscription = resultat.inscription();
        return new AbandonManuelReponse(inscription.id(), inscription.dossard(), inscription.statut(),
                inscription.motifAbandon().orElseThrow(), inscription.boucleAbandon().orElseThrow(),
                resultat.statutCourse());
    }
}
