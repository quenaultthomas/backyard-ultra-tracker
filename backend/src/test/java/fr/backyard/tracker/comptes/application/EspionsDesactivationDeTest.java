package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Doubles espions de 8.1 : port de désaffectation et fermeture des sessions, inscrits dans un journal commun. */
final class EspionsDesactivationDeTest {

    static final String DESAFFECTATION = "desaffectation";
    static final String FERMETURE_TOUTES_SESSIONS = "fermeture-toutes-sessions";
    static final String FERMETURE_AUTRES_SESSIONS = "fermeture-autres-sessions";

    private EspionsDesactivationDeTest() {
    }

    static final class DesaffectationEspion implements DesaffectationBenevole {
        final List<UUID> appels = new ArrayList<>();
        int nombreRetourne;
        RuntimeException panne;
        private final List<String> journal;

        DesaffectationEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public int desaffecterDesCoursesEnPreparation(UUID benevoleId) {
            journal.add(DESAFFECTATION);
            appels.add(benevoleId);
            if (panne != null) {
                throw panne;
            }
            return nombreRetourne;
        }
    }

    static final class SessionsEspion implements InvalidationAutresSessions {
        final List<UUID> toutesLesSessions = new ArrayList<>();
        final List<UUID> autresSessions = new ArrayList<>();
        private final List<String> journal;

        SessionsEspion(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public void invaliderAutresSessions(UUID compteId) {
            journal.add(FERMETURE_AUTRES_SESSIONS);
            autresSessions.add(compteId);
        }

        @Override
        public void invaliderToutesLesSessions(UUID compteId) {
            journal.add(FERMETURE_TOUTES_SESSIONS);
            toutesLesSessions.add(compteId);
        }
    }
}
