package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CA23 (incrément 8.4) : documentation, déploiement, référentiel et socle de test, vérifiés dans les fichiers du dépôt. */
class ConnexionPlafondAdresseDeploiementIntegrationTest {

    static final Path RACINE = Path.of("..").toAbsolutePath().normalize();
    static final String MAX = "CONNEXION_ECHECS_PAR_IP_MAX";
    static final String FENETRE = "CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES";

    private static String lire(String fichier) throws IOException {
        return Files.readString(RACINE.resolve(fichier));
    }

    @Test
    @DisplayName("CA23 : .env.example décrit les deux variables avec défauts 20 et 60 et bornes")
    void ca23_env_example() throws IOException {
        String env = lire(".env.example");
        assertThat(env).containsPattern("(?m)^" + MAX + "=20$").containsPattern("(?m)^" + FENETRE + "=60$")
                .contains("1000").contains("3600");
    }

    @Test
    @DisplayName("CA23 : docker-compose.yml passe les deux variables à api avec leur défaut, docker-compose.e2e.yml fixe le seuil à 100")
    void ca23_compose() throws IOException {
        String compose = lire("docker-compose.yml");
        assertThat(compose).contains(MAX + ": ${" + MAX + ":-20}").contains(FENETRE + ": ${" + FENETRE + ":-60}");
        assertThat(lire("docker-compose.prod.yml")).doesNotContain(MAX);
        assertThat(lire("docker-compose.e2e.yml")).containsPattern(MAX + ":\\s*100\\b");
    }

    @Test
    @DisplayName("CA23 : application.yml porte les deux propriétés avec leurs défauts, le socle de test relève le seuil à 1000")
    void ca23_application_yml_et_socle_de_test() throws IOException {
        String yaml = lire("backend/src/main/resources/application.yml");
        assertThat(yaml).contains("${" + MAX + ":20}").contains("${" + FENETRE + ":60}");
        assertThat(lire("backend/src/test/resources/application.properties"))
                .containsPattern("(?m)^backyard\\.connexion\\.echecs-par-ip-max=1000$");
    }

    @Test
    @DisplayName("CA23 : docs/deploiement.md documente les variables, la validation, et n'affirme plus l'absence de limite par adresse")
    void ca23_documentation_de_deploiement() throws IOException {
        String doc = lire("docs/deploiement.md");
        assertThat(doc).contains(MAX).contains(FENETRE).contains("backyard.connexion.echecs-par-ip-max")
                .contains("backyard.connexion.echecs-par-ip-fenetre-secondes").contains("Plafond d'échecs par adresse")
                .contains("Wifi partagé").contains("Jour de course");
        assertThat(doc).doesNotContain("Il n'y a pas de limite de débit par adresse IP sur la connexion");
    }

    @Test
    @DisplayName("CA23 : le référentiel d'audit contient la ligne SEC-14")
    void ca23_referentiel_sec_14() throws IOException {
        String referentiel = lire("docs/audits/referentiel.md");
        assertThat(referentiel).contains("| SEC-14 | Plafond d'échecs de connexion par adresse cliente (fenêtre glissante)")
                .containsPattern("\\| SEC-14 \\|[^\\n]*\\| P2 \\| 8\\.4 \\|");
    }
}
