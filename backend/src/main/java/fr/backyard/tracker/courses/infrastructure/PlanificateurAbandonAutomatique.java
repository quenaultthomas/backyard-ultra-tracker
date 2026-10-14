package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.application.AbandonnerCoureursHorsDelai;
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
 * transaction. L'échec d'une Course est journalisé et retenté au tick suivant, sans empêcher les autres. Le tick
 * tourne en temps réel ; la décision ne dépend que du bean Clock, lu par le cas d'usage. Aucun journal pour un tick
 * sans effet. Désactivable par {@code backyard.abandon-automatique.actif=false} (tests d'intégration).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "backyard.abandon-automatique.actif", havingValue = "true", matchIfMissing = true)
public class PlanificateurAbandonAutomatique {

    private static final Logger JOURNAL = LoggerFactory.getLogger(PlanificateurAbandonAutomatique.class);

    private final AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai;

    public PlanificateurAbandonAutomatique(AbandonnerCoureursHorsDelai abandonnerCoureursHorsDelai) {
        this.abandonnerCoureursHorsDelai = abandonnerCoureursHorsDelai;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void controlerLesCoursesEnCours() {
        abandonnerCoureursHorsDelai.coursesAControler().forEach(this::controler);
    }

    /** Journalisé en ERROR avec l'identifiant de la Course seulement, puis retenté au tick suivant. */
    private void controler(UUID courseId) {
        try {
            abandonnerCoureursHorsDelai.executer(courseId);
        } catch (RuntimeException echec) {
            JOURNAL.error("Abandon automatique en échec pour la course {}, nouvel essai au prochain contrôle",
                    courseId, echec);
        }
    }
}
