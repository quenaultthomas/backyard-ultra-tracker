package fr.backyard.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active les tâches planifiées côté serveur (clôture des yards, RG17).
 * Désactivable par {@code backyard.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "backyard.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
