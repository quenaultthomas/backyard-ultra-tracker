package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.application.ControlerAbandonsAEcheance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenchement côté serveur de l'abandon automatique : chaque seconde (premier tick une seconde après le démarrage),
 * le planificateur appelle {@link ControlerAbandonsAEcheance}, qui décide quand trancher une Boucle et borne son journal
 * d'erreurs (7.2 RG8, RG9, DEP-05). Aucune autre logique ici. Désactivable par
 * {@code backyard.abandon-automatique.actif=false} (tests d'intégration).
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
