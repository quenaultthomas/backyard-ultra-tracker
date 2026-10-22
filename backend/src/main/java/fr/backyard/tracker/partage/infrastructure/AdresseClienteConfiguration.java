package fr.backyard.tracker.partage.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Une seule définition de l'adresse cliente (7.3 RG4) : {@code backyard.limite-debit-public.proxy-de-confiance}
 * (variable BACKYARD_PROXY_DE_CONFIANCE) désigne le seul pair dont le {@code X-Forwarded-For} est retenu, pour la
 * limite de débit public comme pour le plafond de hachage par adresse.
 */
@Configuration(proxyBeanMethods = false)
public class AdresseClienteConfiguration {

    public static final String PROPRIETE_PROXY = "backyard.limite-debit-public.proxy-de-confiance";

    @Bean
    AdresseCliente adresseCliente(@Value("${" + PROPRIETE_PROXY + ":}") String proxyDeConfiance) {
        return new AdresseCliente(proxyDeConfiance);
    }

    /**
     * Place la valve de l'adresse du pair en tête, avant la RemoteIpValve ajoutée par Spring Boot, et retire à
     * celle-ci la lecture de {@code X-Forwarded-Proto} (7.3 RG2) : sinon Tomcat marque la requête sécurisée et ajoute
     * {@code Secure} à JSESSIONID quel que soit {@code COOKIES_SECURE}.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> valveAdressePair() {
        return new PremiereValve();
    }

    private static final class PremiereValve
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        @Override
        public void customize(TomcatServletWebServerFactory fabrique) {
            List<Valve> valves = new ArrayList<>();
            valves.add(new ValveAdressePair());
            valves.addAll(fabrique.getEngineValves());
            valves.stream().filter(RemoteIpValve.class::isInstance).map(RemoteIpValve.class::cast)
                    .forEach(valve -> valve.setProtocolHeader(null));
            fabrique.setEngineValves(valves);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
