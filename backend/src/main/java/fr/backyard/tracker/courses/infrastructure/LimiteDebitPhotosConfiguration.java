package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.partage.domaine.AdressePhoto;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limite de débit de {@code GET /api/photos/*} (10.2 RG6) : même mécanisme que {@link LimiteDebitPublicConfiguration},
 * avec son propre plafond et ses propres compteurs, pour que les chargements d'images n'entament pas le plafond du
 * suivi. {@code backyard.limite-debit-photos.par-minute} (variable BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE, entier
 * >= 0, 0 = aucune limite ; valeur invalide : l'api ne démarre pas). Même proxy de confiance que la limite publique.
 */
@Configuration(proxyBeanMethods = false)
public class LimiteDebitPhotosConfiguration {

    static final String PROPRIETE_PAR_MINUTE = "backyard.limite-debit-photos.par-minute";

    /** La limite n'est pas un bean : seul ce filtre l'utilise, sans ambiguïté avec celle de /api/courses. */
    @Bean
    FilterRegistrationBean<FiltreLimiteDebitPublic> filtreLimiteDebitPhotos(
            @Value("${" + PROPRIETE_PAR_MINUTE + "}") String parMinute,
            @Value("${" + LimiteDebitPublicConfiguration.PROPRIETE_PROXY + ":}") String proxyDeConfiance,
            Clock horloge, JsonMapper jsonMapper) {
        LimiteDebitPublic limite = new LimiteDebitPublic(AdressePhoto.ROUTE,
                LimiteDebitPublicConfiguration.entierPositifOuNul(parMinute, PROPRIETE_PAR_MINUTE,
                        "BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE"), horloge);
        return LimiteDebitPublicConfiguration.enregistrer(limite, proxyDeConfiance, jsonMapper, AdressePhoto.ROUTE);
    }
}
