package fr.backyard.tracker.partage.infrastructure;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Envoi multipart refusé (413, correctif 431 RG4) : Tomcat continue de lire et de jeter le corps après la réponse
 * d'erreur, dans la limite de {@code server.tomcat.max-swallow-size} (RG3), au lieu de laisser des octets non lus sur
 * la connexion réutilisée par la requête suivante. Ne touche ni aux valves ni à leur ordre.
 */
@Configuration(proxyBeanMethods = false)
public class TeleversementConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> absorptionDesTeleversementsRefuses() {
        return fabrique -> fabrique.addContextCustomizers(contexte -> contexte.setSwallowAbortedUploads(true));
    }
}
