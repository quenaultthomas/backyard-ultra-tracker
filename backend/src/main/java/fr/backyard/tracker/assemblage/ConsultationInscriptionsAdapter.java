package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.courses.application.CompteADesInscriptions;
import fr.backyard.tracker.courses.application.CoursesNonTermineesDuCompte;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port ConsultationInscriptions de comptes par les cas d'usage de lecture de
 * courses, dans la transaction de la suppression ou du changement de rôle du Compte. Exception nommée des règles
 * ArchUnit (9.6a RG9), comme {@link AnnulationInscriptionsAdapter} et {@link DesaffectationBenevoleAdapter}.
 */
@Component
public class ConsultationInscriptionsAdapter implements ConsultationInscriptions {

    private final CompteADesInscriptions compteADesInscriptions;
    private final CoursesNonTermineesDuCompte coursesNonTermineesDuCompte;

    public ConsultationInscriptionsAdapter(CompteADesInscriptions compteADesInscriptions,
                                           CoursesNonTermineesDuCompte coursesNonTermineesDuCompte) {
        this.compteADesInscriptions = compteADesInscriptions;
        this.coursesNonTermineesDuCompte = coursesNonTermineesDuCompte;
    }

    @Override
    public boolean aDesInscriptions(UUID compteId) {
        return compteADesInscriptions.aDesInscriptions(compteId);
    }

    /** Traduction du statut de courses vers celui de comptes ; une Course TERMINEE n'est jamais renvoyée. */
    @Override
    public List<CourseNonTerminee> coursesNonTerminees(UUID compteId) {
        return coursesNonTermineesDuCompte.lister(compteId).stream()
                .map(course -> new CourseNonTerminee(course.id(), course.nom(), traduire(course.statut())))
                .toList();
    }

    /** Sans switch sur l'énumération : il produirait une classe synthétique hors des règles ArchUnit. */
    private static CourseNonTerminee.Statut traduire(StatutCourse statut) {
        if (statut == StatutCourse.EN_PREPARATION) {
            return CourseNonTerminee.Statut.EN_PREPARATION;
        }
        if (statut == StatutCourse.EN_COURS) {
            return CourseNonTerminee.Statut.EN_COURS;
        }
        throw new IllegalStateException("Une Course TERMINEE n'est pas une Course non terminée");
    }
}
