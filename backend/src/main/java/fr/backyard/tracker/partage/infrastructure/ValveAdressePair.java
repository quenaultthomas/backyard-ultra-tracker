package fr.backyard.tracker.partage.infrastructure;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

/**
 * Première valve Tomcat : conserve l'adresse du pair TCP dans un attribut de requête, avant que la valve
 * {@code RemoteIpValve} (forward-headers-strategy native) ne la remplace par celle de {@code X-Forwarded-For}.
 * {@link AdresseCliente} ne retient cet en-tête que si le pair est le proxy de confiance.
 */
final class ValveAdressePair extends ValveBase {

    static final String ATTRIBUT_ADRESSE_PAIR = ValveAdressePair.class.getName() + ".adressePair";

    ValveAdressePair() {
        super(true);
    }

    @Override
    public void invoke(Request requete, Response reponse) throws IOException, ServletException {
        requete.setAttribute(ATTRIBUT_ADRESSE_PAIR, requete.getRemoteAddr());
        getNext().invoke(requete, reponse);
    }
}
