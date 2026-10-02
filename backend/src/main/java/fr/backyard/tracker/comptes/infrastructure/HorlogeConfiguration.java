package fr.backyard.tracker.comptes.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge de l'application, injectée dans les cas d'usage (UTC). */
@Configuration(proxyBeanMethods = false)
public class HorlogeConfiguration {

    @Bean
    Clock horloge() {
        return Clock.systemUTC();
    }
}
