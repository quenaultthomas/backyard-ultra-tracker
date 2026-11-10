package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Correctif 431 après un 413 de téléversement : contrôles de fichiers du dépôt (CA4, CA10). */
class TeleversementDepotIntegrationTest {

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

    /** Bloc {@code handle <motif> {...}} à un niveau d'indentation d'une tabulation, accolades comprises. */
    private static String bloc(String texte, String entete) {
        int debut = texte.indexOf("\n\t" + entete + " {");
        assertThat(debut).as("bloc " + entete).isGreaterThanOrEqualTo(0);
        int fin = texte.indexOf("\n\t}\n", debut);
        assertThat(fin).as("fin du bloc " + entete).isGreaterThan(debut);
        return texte.substring(debut, fin + 4);
    }

    @Test
    @DisplayName("CA4 : le Caddyfile limite /api/* à 8MiB et tamponne 8MiB, sans 4MiB, et /api/sante et /api/version restent sans tampon")
    void ca4_caddyfile_limite_et_tampon() throws IOException {
        String caddy = lire("frontend/Caddyfile");
        String api = bloc(caddy, "handle /api/*");
        assertThat(api).containsPattern("request_body\\s*\\{\\s*max_size 8MiB\\s*\\}");
        assertThat(api).containsPattern("reverse_proxy api:8080\\s*\\{[^}]*request_buffers 8MiB[^}]*\\}");
        assertThat(caddy).doesNotContain("4MiB");
        assertThat(bloc(caddy, "handle /api/sante")).doesNotContain("request_body", "request_buffers");
        assertThat(bloc(caddy, "handle /api/version")).doesNotContain("request_body", "request_buffers");
    }

    @Test
    @DisplayName("CA10 : docs/deploiement.md contient la section « Envois volumineux refusés (413) » complète et un renvoi depuis « Ressources : mémoire et journaux »")
    void ca10_documentation_de_deploiement() throws IOException {
        String doc = lire("docs/deploiement.md");
        int debut = doc.indexOf("### Envois volumineux refusés (413)");
        assertThat(debut).as("section Envois volumineux refusés (413)").isGreaterThanOrEqualTo(0);
        int fin = doc.indexOf("\n### ", debut + 5);
        String section = fin < 0 ? doc.substring(debut) : doc.substring(debut, fin);
        assertThat(section).contains("WEB_MEMOIRE_MAX", "request_buffers", "max-swallow-size", "OOMKilled", "384m",
                "risque accepté");

        int debutRessources = doc.indexOf("### Ressources : mémoire et journaux");
        assertThat(debutRessources).as("section Ressources : mémoire et journaux").isGreaterThanOrEqualTo(0);
        int finRessources = doc.indexOf("\n### ", debutRessources + 5);
        String ressources = doc.substring(debutRessources, finRessources < 0 ? doc.length() : finRessources);
        assertThat(ressources).contains("Envois volumineux refusés (413)");
    }
}
