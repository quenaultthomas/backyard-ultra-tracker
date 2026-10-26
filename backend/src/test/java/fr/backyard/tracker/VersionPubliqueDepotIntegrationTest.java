package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.3 : contrôles de fichiers du dépôt (CA6 à CA8). */
class VersionPubliqueDepotIntegrationTest {

    private static final Path RACINE = racine();

    private static Path racine() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null && !Files.exists(courant.resolve("CHANGELOG.md"))) {
            courant = courant.getParent();
        }
        if (courant == null) {
            throw new IllegalStateException("Racine du dépôt introuvable");
        }
        return courant;
    }

    private static String lire(String chemin) throws IOException {
        return Files.readString(RACINE.resolve(chemin), StandardCharsets.UTF_8);
    }

    private static String section(String texte, String debutTitre, String prefixeSuivant) {
        int debut = texte.indexOf(debutTitre);
        assertThat(debut).as("section " + debutTitre).isGreaterThanOrEqualTo(0);
        int fin = texte.indexOf(prefixeSuivant, debut + debutTitre.length());
        return fin < 0 ? texte.substring(debut) : texte.substring(debut, fin);
    }

    @Test
    @DisplayName("CA6 : pom sans build-info (plugin conservé) et application.yml désactive le bloc build")
    void ca6_pom_et_configuration() throws IOException {
        String pom = lire("backend/pom.xml");
        assertThat(pom).doesNotContain("informations-de-build", "build-info").contains("spring-boot-maven-plugin");

        String yml = lire("backend/src/main/resources/application.yml");
        assertThat(yml).contains("${APP_VERSION:dev}");
        assertThat(Pattern.compile("(?m)^\\s+build:\\s*\\R\\s+enabled: false").matcher(yml).find()).isTrue();
        assertThat(Pattern.compile("(?m)^\\s+env:\\s*\\R\\s+enabled: true").matcher(yml).find()).isTrue();
    }

    @Test
    @DisplayName("CA7 : le Caddyfile réécrit /api/version et /api/sante vers l'actuator, sans autre rewrite actuator")
    void ca7_caddyfile_alias_actuator() throws IOException {
        String caddy = lire("frontend/Caddyfile");
        String version = section(caddy, "handle /api/version", "handle /api/*");
        assertThat(version).contains("rewrite * /actuator/info").contains("no-store");
        assertThat(section(caddy, "handle /api/sante", "handle /api/version")).contains("rewrite * /actuator/health");

        long rewritesActuator = caddy.lines().filter(l -> l.trim().startsWith("rewrite") && l.contains("/actuator/")).count();
        assertThat(rewritesActuator).isEqualTo(2);
    }

    @Test
    @DisplayName("CA7 : le Caddyfile est inchangé par rapport à main")
    void ca7_caddyfile_inchange_par_rapport_a_main() throws Exception {
        Process p;
        try {
            p = new ProcessBuilder("git", "diff", "--quiet", "main", "--", "frontend/Caddyfile")
                    .directory(RACINE.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "git indisponible");
            return;
        }
        assumeTrue(p.waitFor(30, TimeUnit.SECONDS), "git diff trop long");
        int code = p.exitValue();
        assumeTrue(code == 0 || code == 1, "branche main indisponible (code " + code + ")");
        assertThat(code).as("frontend/Caddyfile modifié par rapport à main").isZero();
    }

    @Test
    @DisplayName("CA8 : référentiel SEC-17 unique (P2, 8.3), CHANGELOG et documentation de déploiement")
    void ca8_documentation() throws IOException {
        List<String> lignes = lire("docs/audits/referentiel.md").lines().filter(l -> l.startsWith("| SEC-17 |")).toList();
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).contains("| P2 |").endsWith("| 8.3 |");

        String changelog = lire("CHANGELOG.md");
        String nonPublie = section(changelog, "## [Non publié]", "\n## [0.");
        assertThat(nonPublie).contains("/api/version", "SEC-17", "PEN-03");

        String deploiement = lire("docs/deploiement.md");
        assertThat(section(deploiement, "7. Vérifier le site", "\n8.")).contains("{\"app\":{\"version\"");
        assertThat(section(deploiement, "\n## Restauration", "\n## ")).contains("/api/version");
        assertThat(section(deploiement, "\n## Répétition générale", "\n## ")).contains("/api/version");
    }
}
