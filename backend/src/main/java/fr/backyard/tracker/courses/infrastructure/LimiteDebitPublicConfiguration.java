package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.partage.infrastructure.AdresseCliente;
import fr.backyard.tracker.partage.infrastructure.AdresseClienteConfiguration;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limite de débit des routes publiques (7.2 RG4, RG5) : {@code backyard.limite-debit-public.par-minute}
 * (variable BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE, entier >= 0, 0 = aucune limite ; valeur invalide : l'api ne
 * démarre pas) et {@code backyard.limite-debit-public.proxy-de-confiance} (BACKYARD_PROXY_DE_CONFIANCE, seul pair
 * dont le {@code X-Forwarded-For} est retenu, voir {@link AdresseCliente}). Le filtre passe avant la chaîne Spring
 * Security.
 */
@Configuration(proxyBeanMethods = false)
public class LimiteDebitPublicConfiguration {

    static final String PROPRIETE_PAR_MINUTE = "backyard.limite-debit-public.par-minute";
    static final String PROPRIETE_PROXY = AdresseClienteConfiguration.PROPRIETE_PROXY;
    /** Avant la chaîne Spring Security (ordre -100 par défaut) et après l'encodage des caractères. */
    static final int ORDRE_DU_FILTRE = -110;

    @Bean
    LimiteDebitPublic limiteDebitPublic(@Value("${" + PROPRIETE_PAR_MINUTE + "}") String parMinute, Clock horloge) {
        return new LimiteDebitPublic(entierPositifOuNul(parMinute), horloge);
    }

    @Bean
    FilterRegistrationBean<FiltreLimiteDebitPublic> filtreLimiteDebitPublic(LimiteDebitPublic limiteDebitPublic,
            @Value("${" + PROPRIETE_PROXY + ":}") String proxyDeConfiance, JsonMapper jsonMapper) {
        FilterRegistrationBean<FiltreLimiteDebitPublic> enregistrement = new FilterRegistrationBean<>(
                new FiltreLimiteDebitPublic(limiteDebitPublic, new AdresseCliente(proxyDeConfiance), jsonMapper));
        enregistrement.setOrder(ORDRE_DU_FILTRE);
        enregistrement.addUrlPatterns("/api/courses", "/api/courses/*");
        return enregistrement;
    }

    private static int entierPositifOuNul(String valeur) {
        try {
            int limite = Integer.parseInt(valeur.trim());
            if (limite >= 0) {
                return limite;
            }
        } catch (NumberFormatException exception) {
            throw invalide(valeur, exception);
        }
        throw invalide(valeur, null);
    }

    private static IllegalStateException invalide(String valeur, Exception cause) {
        return new IllegalStateException(PROPRIETE_PAR_MINUTE + " (BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE) doit être "
                + "un entier supérieur ou égal à 0 (reçu : « " + valeur + " »).", cause);
    }
}
