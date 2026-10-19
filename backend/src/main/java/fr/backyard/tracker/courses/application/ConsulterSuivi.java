package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerInscritsCourse.Inscrit;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suivi public d'une Course : la Course, son chronométrage à l'instant de l'horloge (lu une seule fois) et ses
 * coureurs répartis selon le statut enregistré de leur Inscription (jamais recalculé). Au plus trois lectures, dans
 * cet ordre : la Course, puis ses Inscriptions, puis les pseudos ; aucune Inscription lue tant que la Course n'expose
 * pas ses coureurs (EN_PREPARATION), aucun Passage lu. Lire la Course avant les Inscriptions garantit qu'une Course
 * TERMINEE est vue avec ses statuts finaux (écrits dans la même transaction). Lecture seule, aucun journal en
 * fonctionnement normal (lecture fréquente et publique).
 */
@Service
public class ConsulterSuivi {

    /** Coureur en course ou vainqueur : dossard et pseudo. */
    public record CoureurSuivi(int dossard, String pseudo) {

        /** Dossard uniquement : jamais de pseudo dans les journaux. */
        @Override
        public String toString() {
            return "CoureurSuivi[dossard=" + dossard + "]";
        }
    }

    /** Abandon : Boucle et motif vides pour un abandon antérieur enregistré sans eux. */
    public record AbandonSuivi(int dossard, String pseudo, Optional<Integer> boucleAbandon,
                               Optional<MotifAbandon> motifAbandon) {

        /** Dossard uniquement : jamais de pseudo dans les journaux. */
        @Override
        public String toString() {
            return "AbandonSuivi[dossard=" + dossard + "]";
        }
    }

    /**
     * Instantané : chronométrage vide hors EN_COURS ou sans heure de départ ; en course et vainqueurs par dossard,
     * abandons selon {@link Inscription#ORDRE_DES_ABANDONS}.
     */
    public record Suivi(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage,
                        List<CoureurSuivi> coureursEnCourse, List<AbandonSuivi> abandons,
                        List<CoureurSuivi> vainqueurs) {

        public Suivi {
            coureursEnCourse = List.copyOf(coureursEnCourse);
            abandons = List.copyOf(abandons);
            vainqueurs = List.copyOf(vainqueurs);
        }

        /** Identifiant de la Course uniquement. */
        @Override
        public String toString() {
            return "Suivi[course=" + course.id() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final AnnuairePseudos annuairePseudos;
    private final Clock horloge;

    public ConsulterSuivi(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                          AnnuairePseudos annuairePseudos, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.annuairePseudos = annuairePseudos;
        this.horloge = horloge;
    }

    /** @throws CourseIntrouvableException aucune Course pour cet identifiant */
    @Transactional(readOnly = true)
    public Suivi executer(UUID courseId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        Instant maintenant = horloge.instant();
        Optional<Chronometrage> chronometrage = course.chronometrage(maintenant);
        if (!course.exposeSesCoureursAuPublic()) {
            return new Suivi(course, maintenant, chronometrage, List.of(), List.of(), List.of());
        }
        List<Inscription> inscriptions = depotInscriptions.parCourse(courseId);
        List<Inscrit> parDossard = ListerInscritsCourse.inscritsParDossard(inscriptions, annuairePseudos);
        return new Suivi(course, maintenant, chronometrage, coureurs(parDossard, StatutInscription.EN_COURSE),
                abandons(inscriptions, parDossard), coureurs(parDossard, StatutInscription.VAINQUEUR));
    }

    private static List<CoureurSuivi> coureurs(List<Inscrit> parDossard, StatutInscription statut) {
        return parDossard.stream().filter(inscrit -> inscrit.statut() == statut)
                .map(inscrit -> new CoureurSuivi(inscrit.dossard(), inscrit.pseudo())).toList();
    }

    private static List<AbandonSuivi> abandons(List<Inscription> inscriptions, List<Inscrit> inscrits) {
        Map<UUID, String> pseudos = inscrits.stream()
                .collect(Collectors.toMap(Inscrit::inscriptionId, Inscrit::pseudo));
        return inscriptions.stream().filter(inscription -> inscription.statut() == StatutInscription.ABANDON)
                .sorted(Inscription.ORDRE_DES_ABANDONS)
                .map(abandon(pseudos)).toList();
    }

    private static Function<Inscription, AbandonSuivi> abandon(Map<UUID, String> pseudos) {
        return inscription -> new AbandonSuivi(inscription.dossard(), pseudos.get(inscription.id()),
                inscription.boucleAbandon(), inscription.motifAbandon());
    }
}
