package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inscrits d'une Course pour l'administration, quel que soit son statut : lecture seule, triés par dossard, avec le
 * pseudo de chaque coureur obtenu en une seule recherche dans l'annuaire. Le journal ne contient ni pseudo ni
 * identifiant de Compte.
 */
@Service
public class ListerInscritsCourse {

    static final String PSEUDO_COMPTE_INCONNU = "Compte inconnu";

    private static final Logger JOURNAL = System.getLogger(ListerInscritsCourse.class.getName());

    /** Ligne d'un inscrit : jamais de jeton QR ni d'identifiant de Compte. */
    public record Inscrit(UUID inscriptionId, int dossard, String pseudo, StatutInscription statut) {
    }

    /** Inscrits de la Course et nombres calculés à partir de la même lecture. */
    public record InscritsCourse(Course course, List<Inscrit> inscrits, int placesRestantes, boolean complete) {

        public int nombreInscrits() {
            return inscrits.size();
        }

        @Override
        public String toString() {
            return "InscritsCourse[course=" + course.id() + ", nombreInscrits=" + inscrits.size() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final AnnuairePseudos annuairePseudos;

    public ListerInscritsCourse(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                                AnnuairePseudos annuairePseudos) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.annuairePseudos = annuairePseudos;
    }

    /** @throws CourseIntrouvableException aucune Course pour cet identifiant */
    @Transactional(readOnly = true)
    public InscritsCourse executer(UUID courseId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        List<Inscription> inscriptions = depotInscriptions.parCourse(courseId).stream()
                .sorted(Comparator.comparingInt(Inscription::dossard)).toList();
        Map<UUID, String> pseudos = pseudosDe(inscriptions);
        List<Inscrit> inscrits = inscriptions.stream().map(inscription -> inscrit(inscription, pseudos)).toList();
        return new InscritsCourse(course, inscrits, course.placesRestantes(inscrits.size()),
                course.estComplete(inscrits.size()));
    }

    private Map<UUID, String> pseudosDe(List<Inscription> inscriptions) {
        if (inscriptions.isEmpty()) {
            return Map.of();
        }
        return annuairePseudos.pseudosDe(inscriptions.stream().map(Inscription::compteId).distinct().toList());
    }

    private static Inscrit inscrit(Inscription inscription, Map<UUID, String> pseudos) {
        String pseudo = pseudos.get(inscription.compteId());
        if (pseudo == null) {
            JOURNAL.log(Level.WARNING, "Pseudo introuvable pour une inscription (course {0}, dossard {1})",
                    inscription.courseId(), String.valueOf(inscription.dossard()));
            pseudo = PSEUDO_COMPTE_INCONNU;
        }
        return new Inscrit(inscription.id(), inscription.dossard(), pseudo, inscription.statut());
    }
}
