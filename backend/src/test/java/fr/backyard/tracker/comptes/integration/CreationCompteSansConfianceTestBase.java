package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * CA14 (incrément 8.5) : quand le pair TCP n'est pas le proxy de confiance (ou qu'aucun proxy n'est configuré),
 * {@code X-Forwarded-For} n'a aucun effet : faire varier l'en-tête ne permet pas d'échapper au plafond de créations.
 */
abstract class CreationCompteSansConfianceTestBase {

    @LocalServerPort
    int port;

    @Autowired
    RegistreCreationsCompteParAdresseEnMemoire registreCreations;

    ClientCreationCompte client;

    @BeforeEach
    void preparer() throws Exception {
        registreCreations.vider();
        client = new ClientCreationCompte(new ApiHttp(port));
    }

    @Test
    @DisplayName("CA14 : 3 créations avec 3 valeurs différentes de X-Forwarded-For, la 4e est refusée quelle que soit la valeur envoyée (IPv4, IPv6), ou sans en-tête ; une seule adresse suivie")
    void ca14_x_forwarded_for_ignore() throws Exception {
        for (int i = 1; i <= 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf("203.0.113." + i).statusCode()).as("création " + i).isEqualTo(201);
        }

        assertThat(client.creerAvecUnPseudoNeuf("203.0.113.200").statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf("203.0.113.1").statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:77::1").statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf(null).statusCode()).isEqualTo(429);
        assertThat(registreCreations.taille()).as("une seule adresse suivie (celle du pair)").isEqualTo(1);
    }
}
