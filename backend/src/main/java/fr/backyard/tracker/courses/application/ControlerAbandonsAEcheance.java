package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Contrôle d'échéance de l'abandon automatique (7.2 RG8, RG9). Les Courses en cours sont lues sans verrou
 * ({@link DepotCourses#enCours}) et, pour chacune, le nombre de Boucles tranchées à l'instant du Clock
 * ({@link Course#bouclesTranchees}, délai de grâce du domaine compris) est comparé à la dernière valeur décidée avec
 * succès, gardée en mémoire. {@link AbandonnerCoureursHorsDelai} (verrou, relectures, décision du domaine) n'est appelé,
 * dans sa propre transaction, que lorsqu'une nouvelle Boucle est tranchée : ce cas d'usage décide seulement quand
 * appeler. Un échec n'avance pas la dernière valeur décidée (nouvel essai à l'appel suivant) et n'empêche pas les
 * autres Courses. Mémoire vide au démarrage : les Boucles dont la grâce s'est écoulée pendant un arrêt sont décidées
 * au premier appel (appel idempotent). Une Course qui n'est plus en cours est oubliée. Aucun journal sans effet.
 *
 * <p>Journal borné (DEP-05) : une panne qui dure (base injoignable, Course en échec) n'est journalisée, trace comprise,
 * qu'aux échecs consécutifs de rang 1, 2, 4, 8… (voir {@link CompteurEchecsConsecutifs}), et son rétablissement une
 * fois. L'échec de la lecture des Courses en cours est traité de même.
 */
@Service
public class ControlerAbandonsAEcheance {

    private static final Logger JOURNAL = System.getLogger(ControlerAbandonsAEcheance.class.getName());
    private static final String LECTURE_DES_COURSES = "la lecture des courses en cours";

    private final DepotCourses depotCourses;
    private final Clock horloge;
    private final AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai;
    /** Par Course en cours : nombre de Boucles tranchées lors du dernier appel réussi du cas d'usage. */
    private final Map<UUID, Integer> dernieresPointees = new ConcurrentHashMap<>();
    private final CompteurEchecsConsecutifs<UUID> echecsParCourse = new CompteurEchecsConsecutifs<>();
    private final CompteurEchecsConsecutifs<String> echecsDeLecture = new CompteurEchecsConsecutifs<>();

    public ControlerAbandonsAEcheance(DepotCourses depotCourses, Clock horloge,
                                      AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai) {
        this.depotCourses = depotCourses;
        this.horloge = horloge;
        this.abandonnerCoureursHorsDelai = abandonnerCoureursHorsDelai;
    }

    public void executer() {
        lireCoursesAControler().ifPresent(courses -> {
            oublierCoursesQuiNeSontPlusEnCours(courses);
            Instant maintenant = horloge.instant();
            courses.forEach(course -> controlerSiNouvelleBoucleTranchee(course, maintenant));
        });
    }

    private Optional<List<Course>> lireCoursesAControler() {
        try {
            List<Course> courses = depotCourses.enCours();
            signalerRetablissement(echecsDeLecture.enregistrerReussite(LECTURE_DES_COURSES), LECTURE_DES_COURSES);
            return Optional.of(courses);
        } catch (RuntimeException echec) {
            int rang = echecsDeLecture.enregistrerEchec(LECTURE_DES_COURSES);
            journaliserEchec("Contrôle d'abandon automatique en échec à la lecture des courses en cours (" + rang
                    + " échec(s) consécutif(s)), nouvel essai au prochain contrôle", rang, echec);
            return Optional.empty();
        }
    }

    private void oublierCoursesQuiNeSontPlusEnCours(List<Course> courses) {
        Set<UUID> enCours = courses.stream().map(Course::id).collect(Collectors.toSet());
        echecsParCourse.conserverSeulement(enCours);
        dernieresPointees.keySet().retainAll(enCours);
    }

    private void controlerSiNouvelleBoucleTranchee(Course course, Instant maintenant) {
        int bouclesTranchees = course.bouclesTranchees(maintenant);
        if (bouclesTranchees > dernieresPointees.getOrDefault(course.id(), 0) && controler(course.id())) {
            dernieresPointees.put(course.id(), bouclesTranchees);
        }
    }

    /** Vrai si le cas d'usage a réussi ; sinon journalisé en ERROR avec l'identifiant de la Course seulement. */
    private boolean controler(UUID courseId) {
        try {
            abandonnerCoureursHorsDelai.executer(courseId);
            signalerRetablissement(echecsParCourse.enregistrerReussite(courseId), "la course " + courseId);
            return true;
        } catch (RuntimeException echec) {
            int rang = echecsParCourse.enregistrerEchec(courseId);
            journaliserEchec("Abandon automatique en échec pour la course " + courseId + " (" + rang
                    + " échec(s) consécutif(s)), nouvel essai au prochain contrôle", rang, echec);
            return false;
        }
    }

    private static void journaliserEchec(String message, int rang, RuntimeException echec) {
        if (CompteurEchecsConsecutifs.doitJournaliser(rang)) {
            JOURNAL.log(Level.ERROR, message, echec);
        }
    }

    private static void signalerRetablissement(int echecsPrecedents, String objet) {
        if (echecsPrecedents > 0) {
            JOURNAL.log(Level.INFO, "Contrôle d'abandon automatique rétabli pour " + objet + " après "
                    + echecsPrecedents + " échec(s) consécutif(s)");
        }
    }
}
