package fr.backyard.tracker.comptes.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** CA18 (c) : le proxy de confiance est un autre hôte que le pair de test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.connexion.echecs-par-ip-max=20",
        "backyard.connexion.echecs-par-ip-fenetre-secondes=60",
        "backyard.limite-debit-public.proxy-de-confiance=198.51.100.9",
        "backyard.hachage.concurrence-max=32",
        "backyard.hachage.concurrence-max-par-ip=1",
        "backyard.hachage.attente-max-millisecondes=120000"})
@Testcontainers
class ConnexionPlafondAdresseProxyIntegrationTest extends ConnexionPlafondAdresseSansConfianceTestBase {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
}
