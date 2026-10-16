package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ReintegrerCoureur;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.List;
import java.util.UUID;

/** Réponse de la réintégration : ni jeton QR, ni identifiant de Compte, ni pseudo, ni statut de Course. */
public record ReintegrationReponse(UUID inscriptionId, int dossard, StatutInscription statut,
                                   List<Integer> bouclesCorrigees) {

    static ReintegrationReponse depuis(ReintegrerCoureur.Resultat resultat) {
        Inscription inscription = resultat.inscription();
        return new ReintegrationReponse(inscription.id(), inscription.dossard(), inscription.statut(),
                List.copyOf(resultat.bouclesCorrigees()));
    }
}
