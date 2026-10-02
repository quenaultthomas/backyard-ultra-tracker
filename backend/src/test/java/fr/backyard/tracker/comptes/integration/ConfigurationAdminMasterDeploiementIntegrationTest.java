package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** CA23 (incrément 1.4) : variables de l'admin master présentes dans les fichiers de configuration et de déploiement. */
class ConfigurationAdminMasterDeploiementIntegrationTest {

    static final String PSEUDO = "ADMIN_MASTER_PSEUDO";
    static final String MOT_DE_PASSE = "ADMIN_MASTER_MOT_DE_PASSE";

    @Test
    @DisplayName("CA23 : .env.example déclare les deux variables sans valeur et sans reste de « à venir en 1.4 »")
    void ca23_env_example() throws IOException {
        List<String> lignes = Files.readAllLines(racine().resolve(".env.example"));

        assertThat(lignes).contains(PSEUDO + "=", MOT_DE_PASSE + "=");
        assertThat(lignes).filteredOn(l -> l.startsWith(MOT_DE_PASSE) || l.startsWith(PSEUDO))
                .allSatisfy(l -> assertThat(l.substring(l.indexOf('=') + 1)).isEmpty());
        assertThat(String.join("\n", lignes)).doesNotContain("à venir en 1.4");
    }

    @Test
    @DisplayName("CA23 : docker-compose.yml transmet les deux variables à api avec un défaut vide")
    void ca23_compose_de_base() throws IOException {
        Map<String, Object> environnement = environnementApi("docker-compose.yml");

        assertThat(environnement.get(PSEUDO)).isEqualTo("${ADMIN_MASTER_PSEUDO:-}");
        assertThat(environnement.get(MOT_DE_PASSE)).isEqualTo("${ADMIN_MASTER_MOT_DE_PASSE:-}");
    }

    @Test
    @DisplayName("CA23 : docker-compose.prod.yml rend les deux variables obligatoires (:?) avec un message citant la variable")
    void ca23_compose_de_production() throws IOException {
        Map<String, Object> environnement = environnementApi("docker-compose.prod.yml");

        assertThat((String) environnement.get(PSEUDO)).startsWith("${ADMIN_MASTER_PSEUDO:?").contains(PSEUDO);
        assertThat((String) environnement.get(MOT_DE_PASSE)).startsWith("${ADMIN_MASTER_MOT_DE_PASSE:?")
                .contains(MOT_DE_PASSE);
    }

    @Test
    @DisplayName("CA23 : application.yml lit les deux variables avec un défaut vide")
    @SuppressWarnings("unchecked")
    void ca23_application_yml() throws IOException {
        Map<String, Object> configuration = charger(Path.of("src/main/resources/application.yml"));
        Map<String, Object> backyard = (Map<String, Object>) configuration.get("backyard");
        Map<String, Object> adminMaster = (Map<String, Object>) backyard.get("admin-master");

        assertThat(adminMaster.get("pseudo")).isEqualTo("${ADMIN_MASTER_PSEUDO:}");
        assertThat(adminMaster.get("mot-de-passe")).isEqualTo("${ADMIN_MASTER_MOT_DE_PASSE:}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environnementApi(String fichier) throws IOException {
        Map<String, Object> services = (Map<String, Object>) charger(racine().resolve(fichier)).get("services");
        Map<String, Object> api = (Map<String, Object>) services.get("api");
        return (Map<String, Object>) api.get("environment");
    }

    private static Map<String, Object> charger(Path fichier) throws IOException {
        // La balise Compose !reset (docker-compose.prod.yml) n'est pas du YAML standard : on l'ignore.
        return new Yaml().load(Files.readString(fichier).replace("!reset", ""));
    }

    /** Racine du dépôt : le répertoire de travail de Maven est backend/. */
    private static Path racine() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null && !Files.exists(courant.resolve(".env.example"))) {
            courant = courant.getParent();
        }
        assertThat(courant).as("racine du dépôt (.env.example)").isNotNull();
        return courant;
    }
}
