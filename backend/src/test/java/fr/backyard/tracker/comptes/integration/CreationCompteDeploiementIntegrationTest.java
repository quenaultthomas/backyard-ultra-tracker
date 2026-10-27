package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CA18 (incrément 8.5) : documentation, déploiement, référentiel et socle de test, vérifiés dans les fichiers du dépôt. */
class CreationCompteDeploiementIntegrationTest {

    static final Path RACINE = Path.of("..").toAbsolutePath().normalize();
    static final String MAX = "CREATION_COMPTE_PAR_IP_MAX";
    static final String FENETRE = "CREATION_COMPTE_PAR_IP_FENETRE_SECONDES";

    private static String lire(String fichier) throws IOException {
        return Files.readString(RACINE.resolve(fichier));
    }

    @Test
    @DisplayName("CA18 : .env.example décrit les deux variables avec défauts 10 et 60 et bornes")
    void ca18_env_example() throws IOException {
        String env = lire(".env.example");
        assertThat(env).containsPattern("(?m)^" + MAX + "=10$").containsPattern("(?m)^" + FENETRE + "=60$")
                .contains("1000").contains("3600");
    }

    @Test
    @DisplayName("CA18 : docker-compose.yml passe les deux variables à api avec leur défaut, docker-compose.prod.yml ne les surcharge pas, docker-compose.e2e.yml fixe le seuil à 1000")
    void ca18_compose() throws IOException {
        String compose = lire("docker-compose.yml");
        assertThat(compose).contains(MAX + ": ${" + MAX + ":-10}").contains(FENETRE + ": ${" + FENETRE + ":-60}");
        assertThat(lire("docker-compose.prod.yml")).doesNotContain(MAX).doesNotContain(FENETRE);
        assertThat(lire("docker-compose.e2e.yml")).containsPattern("(?m)^\\s+" + MAX + ":\\s*1000\\b")
                .doesNotContain(FENETRE);
    }

    @Test
    @DisplayName("CA18 : application.yml porte les deux propriétés avec leurs défauts, le socle de test relève le seuil à 1000")
    void ca18_application_yml_et_socle_de_test() throws IOException {
        String yaml = lire("backend/src/main/resources/application.yml");
        assertThat(yaml).contains("${" + MAX + ":10}").contains("${" + FENETRE + ":60}");
        assertThat(lire("backend/src/test/resources/application.properties"))
                .containsPattern("(?m)^backyard\\.creation-compte\\.par-ip-max=1000$");
    }

    @Test
    @DisplayName("CA18 : docs/deploiement.md documente les variables, la validation, la conduite du jour d'inscription groupée et la durée de PEUPLER=30")
    void ca18_documentation_de_deploiement() throws IOException {
        String doc = lire("docs/deploiement.md");
        assertThat(doc).contains(MAX).contains(FENETRE).contains("backyard.creation-compte.par-ip-max")
                .contains("backyard.creation-compte.par-ip-fenetre-secondes").contains("TROP_DE_REQUETES")
                .contains("Wifi partagé").contains("grep \"Création de compte\"").contains("PEUPLER=30");
    }

    @Test
    @DisplayName("CA18 : le référentiel d'audit contient la ligne SEC-15 (P3, 8.5) après SEC-14, sans toucher SEC-16 ni SEC-17")
    void ca18_referentiel_sec_15() throws IOException {
        String referentiel = lire("docs/audits/referentiel.md");
        assertThat(referentiel).containsPattern("(?m)^\\| SEC-15 \\| Création de Comptes coureur limitée par adresse"
                + " cliente[^\\n]*\\| P3 \\| 8\\.5 \\|$");
        assertThat(referentiel.indexOf("| SEC-14 |")).isLessThan(referentiel.indexOf("| SEC-15 |"));
        assertThat(referentiel).containsPattern("\\| SEC-16 \\|[^\\n]*\\| P2 \\| 8\\.2 \\|")
                .containsPattern("\\| SEC-17 \\|[^\\n]*\\| P2 \\| 8\\.3 \\|");
    }
}
