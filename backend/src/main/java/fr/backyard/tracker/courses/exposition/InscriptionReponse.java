package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.util.UUID;

/** Inscription vue par son coureur : jamais de jeton QR (pas avant 3.3), jamais d'identifiant de Compte. */
public record InscriptionReponse(UUID id, UUID courseId, int dossard, StatutInscription statut) {

    @Override
    public String toString() {
        return "InscriptionReponse[id=" + id + "]";
    }

    static InscriptionReponse depuis(Inscription inscription) {
        return new InscriptionReponse(inscription.id(), inscription.courseId(), inscription.dossard(),
                inscription.statut());
    }
}
