package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Doubles de 9.6a : ports d'annulation et de consultation des Inscriptions, inscrits dans le journal commun des
 * doubles de 9.3 et 9.4 pour vérifier l'ordre des opérations (RG4, RG8, RG9).
 */
final class DoublesAnonymisationDeTest {

    static final String ANNULATION_INSCRIPTIONS = "annulation-inscriptions";
    static final String CONSULTATION_INSCRIPTIONS = "consultation-inscriptions";

    private DoublesAnonymisationDeTest() {
    }

    static final class AnnulationEspion implements AnnulationInscriptions {
        final List<UUID> appels = new ArrayList<>();
        RuntimeException panne;
        private final List<String> journal;

        AnnulationEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int annulerPour(UUID compteId) {
            journal.add(ANNULATION_INSCRIPTIONS);
            appels.add(compteId);
            if (panne != null) {
                throw panne;
            }
            return 0;
        }
    }

    /** Aucun Compte n'a d'Inscription tant qu'il n'a pas été déclaré par {@link #inscrire(UUID)}. */
    static final class ConsultationEspion implements ConsultationInscriptions {
        final List<UUID> appels = new ArrayList<>();
        RuntimeException panne;
        private final Set<UUID> inscrits = new HashSet<>();
        private final List<String> journal;

        ConsultationEspion(List<String> journal) {
            this.journal = journal;
        }

        void inscrire(UUID compteId) {
            inscrits.add(compteId);
        }

        @Override
        public boolean aDesInscriptions(UUID compteId) {
            journal.add(CONSULTATION_INSCRIPTIONS);
            appels.add(compteId);
            if (panne != null) {
                throw panne;
            }
            return inscrits.contains(compteId);
        }
    }
}
