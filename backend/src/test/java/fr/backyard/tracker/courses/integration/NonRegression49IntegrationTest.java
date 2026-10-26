package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CA10 (4.9, RG15) : non-régression et hygiène du dépôt. Les suites 1.x à 4.8 sont rejouées par le build complet ;
 * ce test garde les invariants vérifiables par le code : le temps passe toujours par le Clock injecté et aucun
 * changeset n'est ajouté ni modifié. Les règles d'architecture sont dans VainqueurUniqueArchitectureTest.
 */
class NonRegression49IntegrationTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path CHANGESETS = Path.of("src/main/resources/db/changelog/changesets");
    private static final Pattern HORLOGE_SYSTEME = Pattern.compile(
            "\\b(Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime)\\.now\\(\\s*\\)|currentTimeMillis\\(");

    @Test
    @DisplayName("CA10 : aucune occurrence de Instant.now(), LocalDate.now() sans horloge ou System.currentTimeMillis() dans le code de production")
    void ca10_aucune_lecture_du_temps_hors_clock() throws IOException {
        List<String> fautes;
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            fautes = fichiers.filter(f -> f.toString().endsWith(".java")).flatMap(f -> {
                try {
                    return Files.readAllLines(f).stream().filter(l -> HORLOGE_SYSTEME.matcher(l).find())
                            .map(l -> f + " : " + l.strip());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        assertThat(fautes).isEmpty();
    }

    @Test
    @DisplayName("CA10 : aucun changeset Liquibase ajouté en 4.9 : 0010-abandon-inscription n'est suivi que de 0011-compte-actif (8.1)")
    void ca10_dernier_changeset_inchange() throws IOException {
        try (Stream<Path> fichiers = Files.list(CHANGESETS)) {
            List<String> noms = fichiers.map(f -> f.getFileName().toString()).sorted().toList();

            assertThat(noms.get(noms.size() - 2)).startsWith("0010-abandon-inscription");
            assertThat(noms.get(noms.size() - 1)).startsWith("0011-compte-actif");
        }
    }
}
