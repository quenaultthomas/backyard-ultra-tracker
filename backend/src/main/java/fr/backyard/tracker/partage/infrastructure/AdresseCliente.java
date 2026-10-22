package fr.backyard.tracker.partage.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * IP cliente retenue par la limite de débit public (7.2) et le plafond de hachage par adresse (7.3). Si le pair TCP
 * est le proxy de confiance (nom d'hôte résolu par le DNS, {@code web} dans la stack Compose), l'adresse résolue par
 * Tomcat depuis {@code X-Forwarded-For} (Caddy remplace l'en-tête reçu du client) ; sinon l'adresse du pair
 * elle-même : un {@code X-Forwarded-For} envoyé directement à l'api n'a aucun effet. Sans proxy configuré, toujours
 * l'adresse du pair.
 */
public final class AdresseCliente {

    private static final Logger JOURNAL = LoggerFactory.getLogger(AdresseCliente.class);

    private final Optional<String> proxyDeConfiance;
    private final AtomicBoolean proxyIntrouvableSignale = new AtomicBoolean();

    /** @param proxyDeConfiance nom d'hôte ou adresse du reverse proxy, vide ou null pour aucun */
    public AdresseCliente(String proxyDeConfiance) {
        this.proxyDeConfiance = Optional.ofNullable(proxyDeConfiance).map(String::trim).filter(nom -> !nom.isEmpty());
    }

    public String de(HttpServletRequest requete) {
        Object attribut = requete.getAttribute(ValveAdressePair.ATTRIBUT_ADRESSE_PAIR);
        String adressePair = attribut instanceof String adresse ? adresse : requete.getRemoteAddr();
        return estLeProxyDeConfiance(adressePair) ? requete.getRemoteAddr() : adressePair;
    }

    /** Adresse de la requête HTTP traitée par le thread courant ; vide hors requête (démarrage, tâche planifiée). */
    public Optional<String> deLaRequeteEnCours() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributs
                ? Optional.of(de(attributs.getRequest()))
                : Optional.empty();
    }

    /** Résolution mise en cache par la JVM ; proxy introuvable : non fiable (signalé une fois). */
    private boolean estLeProxyDeConfiance(String adressePair) {
        if (proxyDeConfiance.isEmpty()) {
            return false;
        }
        try {
            InetAddress pair = InetAddress.ofLiteral(adressePair);
            return Arrays.asList(InetAddress.getAllByName(proxyDeConfiance.get())).contains(pair);
        } catch (UnknownHostException | IllegalArgumentException echec) {
            if (proxyIntrouvableSignale.compareAndSet(false, true)) {
                JOURNAL.warn("Proxy de confiance « {} » non résolu ou adresse de pair illisible : X-Forwarded-For "
                        + "ignoré, adresse du pair retenue ({})", proxyDeConfiance.get(), echec.getMessage());
            }
            return false;
        }
    }
}
