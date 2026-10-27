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

/** CA11 (4.3 RG1, RG8, RG10) : fichiers de déploiement du dépôt, chemins relatifs à la racine depuis backend/. */
class DeploiementProfilE2eIntegrationTest {

    static final Path RACINE = Path.of("..").toAbsolutePath().normalize();

    private static String lire(String fichier) throws IOException {
        return Files.readString(RACINE.resolve(fichier));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String fichier) throws IOException {
        return (Map<String, Object>) new Yaml().load(lire(fichier).replace("!reset", ""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(Map<String, Object> compose, String nom) {
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(nom);
    }

    @Test
    @DisplayName("CA11 : docker-compose.e2e.yml ne définit que api.environment.SPRING_PROFILES_ACTIVE=e2e et BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE=6000 (7.2), ni ports, ni volumes, ni restart, ni BACKYARD_ENVIRONNEMENT")
    @SuppressWarnings("unchecked")
    void ca11_compose_e2e_minimal() throws IOException {
        Map<String, Object> compose = yaml("docker-compose.e2e.yml");

        assertThat(compose.keySet()).containsExactly("services");
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertThat(services.keySet()).containsExactly("api");
        Map<String, Object> api = service(compose, "api");
        assertThat(api.keySet()).containsExactly("environment");
        Map<String, Object> environnement = (Map<String, Object>) api.get("environment");
        // 7.2 RG4 : limite de débit relevée à 6000 pour que les tests E2E parallèles ne s'auto-limitent pas
        assertThat(environnement).containsOnly(Map.entry("SPRING_PROFILES_ACTIVE", "e2e"),
                Map.entry("BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE", 6000),
                // 8.4 RG15 : plafond d'échecs par adresse relevé à 100 (tests E2E parallèles issus de la même adresse)
                Map.entry("CONNEXION_ECHECS_PAR_IP_MAX", 100),
                // 8.5 RG9 : plafond de créations de Compte par adresse relevé à 1000 (suite parallèle, même adresse)
                Map.entry("CREATION_COMPTE_PAR_IP_MAX", 1000));
        assertThat(lire("docker-compose.e2e.yml")).doesNotContain("BACKYARD_ENVIRONNEMENT");
        assertThat(lire("docker-compose.e2e.yml")).contains("docker-compose.prod.yml");
    }

    @Test
    @DisplayName("CA11 : docker-compose.yml et docker-compose.prod.yml ne contiennent ni SPRING_PROFILES_ACTIVE ni la chaîne e2e")
    void ca11_compose_commun_et_prod_sans_profil() throws IOException {
        for (String fichier : List.of("docker-compose.yml", "docker-compose.prod.yml")) {
            assertThat(lire(fichier)).as(fichier).doesNotContain("SPRING_PROFILES_ACTIVE").doesNotContain("e2e");
        }
    }

    @Test
    @DisplayName("CA11 : docker-compose.prod.yml fixe api.environment.BACKYARD_ENVIRONNEMENT à la valeur littérale production")
    @SuppressWarnings("unchecked")
    void ca11_compose_prod_fixe_l_environnement() throws IOException {
        Map<String, Object> api = service(yaml("docker-compose.prod.yml"), "api");

        Map<String, Object> environnement = (Map<String, Object>) api.get("environment");
        assertThat(environnement.get("BACKYARD_ENVIRONNEMENT")).isEqualTo("production");
    }

    @Test
    @DisplayName("CA11 : .env.example ne contient BACKYARD_ENVIRONNEMENT et SPRING_PROFILES_ACTIVE qu'en commentaire")
    void ca11_env_example_en_commentaire_seulement() throws IOException {
        List<String> lignes = lire(".env.example").lines().toList();

        assertThat(lignes).anySatisfy(l -> assertThat(l).startsWith("#").contains("BACKYARD_ENVIRONNEMENT"));
        assertThat(lignes).anySatisfy(l -> assertThat(l).startsWith("#").contains("SPRING_PROFILES_ACTIVE"));
        assertThat(lignes.stream().filter(l -> !l.stripLeading().startsWith("#")))
                .noneSatisfy(l -> assertThat(l).containsAnyOf("BACKYARD_ENVIRONNEMENT", "SPRING_PROFILES_ACTIVE"));
    }

    @Test
    @DisplayName("CA11 : docs/deploiement.md contient la section « Tests E2E et profil `e2e` »")
    void ca11_documentation_de_deploiement() throws IOException {
        assertThat(lire("docs/deploiement.md").lines())
                .anySatisfy(l -> assertThat(l).matches("#+ Tests E2E et profil `e2e`\\s*"));
    }
}
