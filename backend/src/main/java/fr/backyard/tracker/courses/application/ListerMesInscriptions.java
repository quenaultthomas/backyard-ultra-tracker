package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inscriptions du Compte demandeur, toutes Courses et tous statuts confondus, chacune avec sa Course, dans l'ordre de
 * liste des Courses défini par le domaine. Lecture seule : les Inscriptions des autres Comptes ne sont jamais lues.
 */
@Service
public class ListerMesInscriptions {

    /** Inscription du Compte demandeur et la Course à laquelle elle se rapporte. */
    public record MonInscription(Course course, Inscription inscription) {
    }

    private static final Comparator<MonInscription> ORDRE_DES_COURSES =
            Comparator.comparing(MonInscription::course, Course.ORDRE_DE_LISTE);

    private final DepotInscriptions depotInscriptions;
    private final DepotCourses depotCourses;

    public ListerMesInscriptions(DepotInscriptions depotInscriptions, DepotCourses depotCourses) {
        this.depotInscriptions = depotInscriptions;
        this.depotCourses = depotCourses;
    }

    @Transactional(readOnly = true)
    public List<MonInscription> executer(UUID compteId) {
        return depotInscriptions.parCompte(compteId).stream()
                .map(this::avecSaCourse)
                .flatMap(Optional::stream)
                .sorted(ORDRE_DES_COURSES)
                .toList();
    }

    /** Vide si la Course a disparu entre les deux lectures (suppression concurrente) : l'Inscription n'existe plus. */
    private Optional<MonInscription> avecSaCourse(Inscription inscription) {
        return depotCourses.parId(inscription.courseId()).map(course -> new MonInscription(course, inscription));
    }
}
