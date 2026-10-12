package fr.backyard.tracker.comptes.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Garde-fou du profil e2e (4.3 RG8), vérifié au démarrage : refuse l'horloge pilotable quand
 * {@code backyard.environnement} vaut {@code production} (fixée par docker-compose.prod.yml), sinon avertit une fois.
 * Le message de refus ne contient aucune valeur de configuration.
 */
public class GardeFouProfilE2e {

    private static final Logger JOURNAL = LoggerFactory.getLogger(GardeFouProfilE2e.class);
    private static final String PROPRIETE_ENVIRONNEMENT = "backyard.environnement";
    private static final String PRODUCTION = "production";

    private final Environment environnement;

    public GardeFouProfilE2e(Environment environnement) {
        this.environnement = environnement;
    }

    /** @throws IllegalStateException en environnement de production */
    public void verifier() {
        String valeur = environnement.getProperty(PROPRIETE_ENVIRONNEMENT, "");
        if (PRODUCTION.equalsIgnoreCase(valeur.strip())) {
            throw new IllegalStateException("Le profil e2e est interdit en production (horloge pilotable).");
        }
        JOURNAL.warn("Profil e2e actif : horloge pilotable, ne jamais utiliser en production.");
    }
}
