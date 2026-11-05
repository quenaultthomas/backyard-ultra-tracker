package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Doubles de 9.6b : port de consultation des Inscriptions complété par la liste des Courses non TERMINEE d'un Compte,
 * inscrit dans le journal commun des doubles pour vérifier l'ordre des opérations (RG17).
 */
final class DoublesChangementRoleDeTest {

    static final String CONSULTATION_COURSES = "consultation-courses-non-terminees";

    private DoublesChangementRoleDeTest() {
    }

    /** Aucune Course non terminée tant que {@link #inscrireA(CourseNonTerminee...)} n'a pas été appelé. */
    static final class ConsultationCoursesEspion implements ConsultationInscriptions {
        final List<UUID> appels = new ArrayList<>();
        RuntimeException panne;
        private final List<CourseNonTerminee> courses = new ArrayList<>();
        private final List<String> journal;

        ConsultationCoursesEspion(List<String> journal) {
            this.journal = journal;
        }

        void inscrireA(CourseNonTerminee... coursesNonTerminees) {
            courses.addAll(List.of(coursesNonTerminees));
        }

        @Override
        public boolean aDesInscriptions(UUID compteId) {
            throw new AssertionError("Le changement de rôle ne doit pas appeler aDesInscriptions (9.6a)");
        }

        @Override
        public List<CourseNonTerminee> coursesNonTerminees(UUID compteId) {
            journal.add(CONSULTATION_COURSES);
            appels.add(compteId);
            if (panne != null) {
                throw panne;
            }
            return List.copyOf(courses);
        }
    }
}
