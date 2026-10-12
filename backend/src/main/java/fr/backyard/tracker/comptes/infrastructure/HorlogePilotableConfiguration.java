package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.application.PiloterHorloge;
import fr.backyard.tracker.comptes.domaine.ReglageHorloge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Composants du seul profil e2e (4.3 RG1) : cas d'usage de pilotage de l'horloge et garde-fou de démarrage. Le bean
 * {@link ReglageHorloge} est l'horloge pilotable déclarée par {@link HorlogeConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
public class HorlogePilotableConfiguration {

    @Bean
    PiloterHorloge piloterHorloge(ReglageHorloge reglageHorloge) {
        return new PiloterHorloge(reglageHorloge);
    }

    /** Vérifié dès la création du bean : un refus fait échouer le démarrage. */
    @Bean(initMethod = "verifier")
    GardeFouProfilE2e gardeFouProfilE2e(Environment environnement) {
        return new GardeFouProfilE2e(environnement);
    }
}
