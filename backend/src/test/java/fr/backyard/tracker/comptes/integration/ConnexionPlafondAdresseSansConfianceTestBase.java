package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE_FAUX;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.infrastructure.RegistreEchecsParAdresseEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * CA18 (c) et (d) de l'incrément 8.4 : quand le pair TCP n'est pas le proxy de confiance (ou qu'aucun proxy n'est
 * configuré), {@code X-Forwarded-For} n'a aucun effet : faire varier l'en-tête ne permet pas d'échapper au plafond.
 */
abstract class ConnexionPlafondAdresseSansConfianceTestBase {

    @LocalServerPort
    int port;

    @Autowired
    RegistreEchecsParAdresseEnMemoire registreAdresses;

    @Autowired
    RegistreTentativesConnexionEnMemoire registrePseudos;

    ClientConnexionParAdresse client;

    @BeforeEach
    void preparer() throws Exception {
        registreAdresses.vider();
        registrePseudos.vider();
        client = new ClientConnexionParAdresse(new ApiHttp(port));
    }

    @Test
    @DisplayName("CA18 : 20 échecs avec 20 valeurs différentes de X-Forwarded-For : la 21e tentative est refusée quelle que soit la valeur envoyée, ou sans en-tête")
    void ca18_x_forwarded_for_ignore() throws Exception {
        for (int i = 1; i <= 20; i++) {
            HttpResponse<String> reponse = client.connecter("203.0.113." + i, "Spray" + i, MOT_DE_PASSE_FAUX);
            assertThat(reponse.statusCode()).as("échec " + i).isEqualTo(401);
        }

        assertThat(client.connecter("203.0.113.200", "Autre", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(client.connecter("203.0.113.1", "Autre", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(client.connecter("2001:db8:77::1", "Autre", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(client.connecter(null, "Autre", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(registreAdresses.taille()).as("une seule adresse suivie (celle du pair)").isEqualTo(1);
    }
}
