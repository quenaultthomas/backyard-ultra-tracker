package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dossard imprimable d'une Inscription pour l'administration, quels que soient les statuts : lecture seule, sans
 * effet de bord ni régénération du jeton. Le pseudo vient de la logique partagée des inscrits. Le journal ne contient
 * que l'identifiant de la Course et le numéro de dossard.
 */
@Service
public class LireDossard {

    private static final Logger JOURNAL = System.getLogger(LireDossard.class.getName());

    /** Dossard lu : {@code toString()} ne montre ni le jeton, ni le pseudo, ni le nom de la Course. */
    public record Dossard(Course course, Inscription inscription, String pseudo) {

        @Override
        public String toString() {
            return "Dossard[inscription=" + inscription.id() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final AnnuairePseudos annuairePseudos;

    public LireDossard(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                       AnnuairePseudos annuairePseudos) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.annuairePseudos = annuairePseudos;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant (contrôlée en premier)
     * @throws InscriptionIntrouvableException Inscription inconnue ou d'une autre Course
     */
    @Transactional(readOnly = true)
    public Dossard executer(UUID courseId, UUID inscriptionId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        Inscription inscription = depotInscriptions.parIdEtCourse(inscriptionId, courseId)
                .orElseThrow(InscriptionIntrouvableException::new);
        String pseudo = ListerInscritsCourse.pseudoDe(inscription, annuairePseudos);
        JOURNAL.log(Level.INFO, "Dossard consulté (course {0}, dossard {1})", course.id(),
                String.valueOf(inscription.dossard()));
        return new Dossard(course, inscription, pseudo);
    }
}
