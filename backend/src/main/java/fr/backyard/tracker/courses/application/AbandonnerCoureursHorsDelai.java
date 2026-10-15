package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AbandonAutomatique;
import fr.backyard.tracker.courses.domaine.AbandonAutomatique.Decision;
import fr.backyard.tracker.courses.domaine.AbandonAutomatique.Designation;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.Passage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abandon automatique des coureurs hors délai d'une Course, déclenché côté serveur. La Course est chargée sous
 * verrou, puis Inscriptions et Passages sont relus et l'horloge lue une seule fois : deux exécutions concurrentes,
 * ou une exécution et un scan, sont sérialisées et la seconde trouve l'état déjà traité. Idempotent : une Course
 * introuvable, qui n'est pas en cours (une Course TERMINEE comprise) ou sans Boucle terminée à décider n'est pas
 * modifiée. Statuts des Inscriptions et de la Course sont écrits dans la même transaction. Journal : une ligne par
 * Boucle ayant produit des abandons et une ligne de fin de course (vainqueur unique ou vainqueurs partagés),
 * identifiant de la Course seulement.
 */
@Service
public class AbandonnerCoureursHorsDelai {

    private static final Logger JOURNAL = System.getLogger(AbandonnerCoureursHorsDelai.class.getName());

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final DepotPassages depotPassages;
    private final Clock horloge;

    public AbandonnerCoureursHorsDelai(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                                       DepotPassages depotPassages, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.depotPassages = depotPassages;
        this.horloge = horloge;
    }

    /** Identifiants des Courses en cours, à contrôler chacune par {@link #executer} dans sa propre transaction. */
    @Transactional(readOnly = true)
    public List<UUID> coursesAControler() {
        return depotCourses.idsEnCours();
    }

    /**
     * Résultat d'une exécution du contrôle.
     *
     * @param abandons nombre d'Inscriptions mises en abandon
     * @param vainqueurs nombre d'Inscriptions déclarées vainqueurs, la Course étant alors terminée
     */
    public record Resultat(int abandons, int vainqueurs) {

        static final Resultat AUCUNE_DECISION = new Resultat(0, 0);
    }

    /**
     * Applique la décision du domaine dans la transaction : abandons, vainqueurs et fin de Course, tout ou rien.
     */
    @Transactional
    public Resultat executer(UUID courseId) {
        Course course = depotCourses.parIdPourModification(courseId).orElse(null);
        if (course == null) {
            return Resultat.AUCUNE_DECISION;
        }
        int bouclesTerminees = course.bouclesTerminees(horloge.instant());
        if (bouclesTerminees == 0) {
            return Resultat.AUCUNE_DECISION;
        }
        List<Inscription> enCourse = depotInscriptions.parCourse(courseId).stream()
                .filter(Inscription::estEnCourse).toList();
        if (enCourse.isEmpty()) {
            return Resultat.AUCUNE_DECISION;
        }
        Map<UUID, Set<Integer>> boucles = bouclesPasseesParInscription(enCourse);
        Decision decision = AbandonAutomatique.decider(enCourse,
                (inscription, boucle) -> boucles.getOrDefault(inscription.id(), Set.of()).contains(boucle),
                bouclesTerminees, course.nombreMaxBoucles());
        appliquer(course, decision);
        journaliser(courseId, decision);
        return new Resultat(decision.abandons().size(), decision.vainqueurs().size());
    }

    private void appliquer(Course course, Decision decision) {
        decision.abandons().forEach(d -> depotInscriptions.mettreAJour(d.inscription().abandonner(d.motif(),
                d.boucleAbandon())));
        decision.vainqueurs().forEach(vainqueur -> depotInscriptions.mettreAJour(vainqueur.devenirVainqueur()));
        if (decision.courseTerminee()) {
            depotCourses.enregistrer(course.terminer());
        }
    }

    private Map<UUID, Set<Integer>> bouclesPasseesParInscription(List<Inscription> inscriptions) {
        return depotPassages.parInscriptions(inscriptions.stream().map(Inscription::id).toList()).stream()
                .collect(Collectors.groupingBy(Passage::inscriptionId,
                        Collectors.mapping(Passage::numeroBoucle, Collectors.toSet())));
    }

    private static void journaliser(UUID courseId, Decision decision) {
        decision.abandons().stream()
                .collect(Collectors.groupingBy(Designation::boucleAbandon, TreeMap::new, Collectors.counting()))
                .forEach((boucle, nombre) -> JOURNAL.log(Level.INFO,
                        "Abandon automatique : course {0}, boucle {1}, {2} inscription(s) en abandon.",
                        courseId, String.valueOf(boucle), String.valueOf(nombre)));
        decision.boucleDeFin().ifPresent(boucle -> journaliserFin(courseId, boucle, decision.vainqueurs().size()));
    }

    private static void journaliserFin(UUID courseId, int boucle, int vainqueurs) {
        if (vainqueurs == 1) {
            JOURNAL.log(Level.INFO, "Fin de course : course {0}, boucle {1}, vainqueur unique.",
                    courseId, String.valueOf(boucle));
        } else {
            JOURNAL.log(Level.INFO, "Fin de course : course {0}, boucle {1}, {2} vainqueurs partagés.",
                    courseId, String.valueOf(boucle), String.valueOf(vainqueurs));
        }
    }
}
