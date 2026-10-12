package fr.backyard.tracker.comptes.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Horloge de l'application, injectée dans les cas d'usage (UTC). Seul endroit qui accède à l'horloge système
 * (4.3 RG9). Un seul bean {@link Clock} dans chaque cas : l'horloge système hors profil e2e, l'horloge pilotable
 * (horloge système plus un décalage) en profil e2e.
 */
@Configuration(proxyBeanMethods = false)
public class HorlogeConfiguration {

    @Bean
    @Profile("!e2e")
    Clock horloge() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("e2e")
    HorlogeDecalee horlogePilotable() {
        return new HorlogeDecalee(Clock.systemUTC());
    }
}
