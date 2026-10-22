package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.application.ControlerAbandonsAEcheance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenchement côté serveur de l'abandon automatique : chaque seconde (premier tick une seconde après le démarrage,
 * ce qui rattrape les Boucles dont le délai de grâce s'est écoulé pendant un arrêt), chaque Course en cours est contrôlée dans sa propre
 * transaction. L'échec d'une Course est retenté au tick suivant, sans empêcher les autres. Le tick tourne en temps réel ;
 * la décision ne dépend que du bean Clock, lu par le cas d'usage, et une Boucle n'est tranchée qu'après le délai de
 * grâce du domaine ({@code Course.bouclesTranchees}). Aucun journal pour un tick sans effet.
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

    private final ControlerAbandonsAEcheance controlerAbandonsAEcheance;

    public PlanificateurAbandonAutomatique(ControlerAbandonsAEcheance controlerAbandonsAEcheance) {
        this.controlerAbandonsAEcheance = controlerAbandonsAEcheance;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void controlerLesCoursesEnCours() {
        controlerAbandonsAEcheance.executer();
    }
}
