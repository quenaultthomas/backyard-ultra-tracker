package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenchement côté serveur de l'abandon automatique : chaque seconde (premier tick une seconde après le démarrage,
 * ce qui rattrape les Boucles terminées pendant un arrêt), chaque Course en cours est contrôlée dans sa propre
 * transaction. L'échec d'une Course est retenté au tick suivant, sans empêcher les autres. Le tick tourne en temps réel ;
 * la décision ne dépend que du bean Clock, lu par le cas d'usage. Aucun journal pour un tick sans effet.
 * Désactivable par {@code backyard.abandon-automatique.actif=false} (tests d'intégration).
 *
 * <p>Journal borné (DEP-05) : une panne qui dure (base injoignable, Course en échec) n'est journalisée, trace comprise,
 * qu'aux échecs consécutifs de rang 1, 2, 4, 8… (voir {@link CompteurEchecsConsecutifs}), et son rétablissement une
 * fois. L'échec de la lecture des Courses en cours est traité de même, au lieu de la trace complète du planificateur
 * Spring à chaque seconde.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "backyard.abandon-automatique.actif", havingValue = "true", matchIfMissing = true)
public class PlanificateurAbandonAutomatique {

    private static final Logger JOURNAL = LoggerFactory.getLogger(PlanificateurAbandonAutomatique.class);
    private static final String LECTURE_DES_COURSES = "la lecture des courses en cours";

    private final AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai;
    private final CompteurEchecsConsecutifs<UUID> echecsParCourse = new CompteurEchecsConsecutifs<>();
    private final CompteurEchecsConsecutifs<String> echecsDeLecture = new CompteurEchecsConsecutifs<>();

    public PlanificateurAbandonAutomatique(AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai) {
        this.abandonnerCoureursHorsDelai = abandonnerCoureursHorsDelai;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void controlerLesCoursesEnCours() {
        lireCoursesAControler().ifPresent(courses -> {
            echecsParCourse.conserverSeulement(courses);
            courses.forEach(this::controler);
        });
    }

    private Optional<List<UUID>> lireCoursesAControler() {
        try {
            List<UUID> courses = abandonnerCoureursHorsDelai.coursesAControler();
            signalerRetablissement(echecsDeLecture.enregistrerReussite(LECTURE_DES_COURSES), LECTURE_DES_COURSES);
            return Optional.of(courses);
        } catch (RuntimeException echec) {
            int rang = echecsDeLecture.enregistrerEchec(LECTURE_DES_COURSES);
            if (CompteurEchecsConsecutifs.doitJournaliser(rang)) {
                JOURNAL.error("Contrôle d'abandon automatique en échec à la lecture des courses en cours "
                        + "({} échec(s) consécutif(s)), nouvel essai au prochain contrôle", rang, echec);
            }
            return Optional.empty();
        }
    }

    /** Journalisé en ERROR avec l'identifiant de la Course seulement, puis retenté au tick suivant. */
    private void controler(UUID courseId) {
        try {
            abandonnerCoureursHorsDelai.executer(courseId);
            signalerRetablissement(echecsParCourse.enregistrerReussite(courseId), "la course " + courseId);
        } catch (RuntimeException echec) {
            int rang = echecsParCourse.enregistrerEchec(courseId);
            if (CompteurEchecsConsecutifs.doitJournaliser(rang)) {
                JOURNAL.error("Abandon automatique en échec pour la course {} ({} échec(s) consécutif(s)), "
                        + "nouvel essai au prochain contrôle", courseId, rang, echec);
            }
        }
    }

    private static void signalerRetablissement(int echecsPrecedents, String objet) {
        if (echecsPrecedents > 0) {
            JOURNAL.info("Contrôle d'abandon automatique rétabli pour {} après {} échec(s) consécutif(s)",
                    objet, echecsPrecedents);
        }
    }
}
