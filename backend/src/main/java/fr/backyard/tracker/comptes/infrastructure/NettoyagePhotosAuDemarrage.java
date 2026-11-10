package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.application.NettoyerPhotosOrphelines;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Balayage unique des photos orphelines à chaque démarrage de l'api (correctif audit jalon 10 RG6), après les
 * migrations Liquibase. Le cas d'usage ne lève jamais d'exception : le démarrage n'est jamais bloqué. Pas de
 * planificateur : un redémarrage relance un balayage.
 */
@Component
public class NettoyagePhotosAuDemarrage implements ApplicationRunner {

    private final NettoyerPhotosOrphelines nettoyerPhotosOrphelines;

    public NettoyagePhotosAuDemarrage(NettoyerPhotosOrphelines nettoyerPhotosOrphelines) {
        this.nettoyerPhotosOrphelines = nettoyerPhotosOrphelines;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        nettoyerPhotosOrphelines.executer();
    }
}
