package fr.backyard.scheduling;

import fr.backyard.service.YardClosingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclencheur périodique de la clôture des yards (RG17). Aucune logique métier : la décision de
 * clôturer (yard écoulé, auto-DNF, fin de course) appartient à {@link YardClosingService}.
 * Une exception levée est journalisée par le planificateur Spring et le déclenchement suivant a lieu normalement.
 */
@Component
public class YardClosingScheduler {

    private final YardClosingService yardClosingService;

    public YardClosingScheduler(YardClosingService yardClosingService) {
        this.yardClosingService = yardClosingService;
    }

    @Scheduled(fixedDelayString = "${backyard.yard-closing.fixed-delay-ms:1000}")
    public void closeElapsedYards() {
        yardClosingService.closeElapsedYards();
    }
}
