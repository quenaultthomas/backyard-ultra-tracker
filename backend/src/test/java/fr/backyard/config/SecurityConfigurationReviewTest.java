package fr.backyard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 3 - CA56, partie « revue de code » automatisee (reserve R3-1, accord fonctionnel du 2026-09-26) :
 * le PasswordEncoder effectif est BCrypt et aucune API Spring Security obsolete ou interdite n'apparait
 * dans {@code backend/src/main}. JUnit pur, sans ArchUnit : analyse textuelle des sources.
 */
@Tag("INC-3")
@Tag("INC3-CA56")
class SecurityConfigurationReviewTest {

    private static final String ADMIN_HASH_FOR_ADMIN_SECRET =
        "$2a$04$y5qZCIAHUZJzXkawr/klsep.f6zWCD7ErOpuAVwd.gwAalNLmDGZi";

    /** API interdites par CA56 / RG33. {@code .and()} vise le chainage sans argument de l'ancienne DSL. */
    private static final List<Pattern> FORBIDDEN_APIS = List.of(
        Pattern.compile("\\bWebSecurityConfigurerAdapter\\b"),
        Pattern.compile("\\.and\\(\\s*\\)"),
        Pattern.compile("\\bantMatchers\\b"),
        Pattern.compile("\\bmvcMatchers\\b"),
        Pattern.compile("\\bNoOpPasswordEncoder\\b"));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SecurityConfig.class)
        .withPropertyValues(
            "backyard.security.admin.username=admin-test",
            "backyard.security.admin.password-hash=" + ADMIN_HASH_FOR_ADMIN_SECRET,
            "backyard.security.scanner.username=scanner-test",
            "backyard.security.scanner.password-hash=$2a$04$OfMV7IR5R5uA64IgejaHN.NPGO6M507T2FWM7.j/113nmUPHwnxg2");

    static Path productionSourceRoot() {
        Path root = Path.of(System.getProperty("basedir", "."), "src", "main").toAbsolutePath().normalize();
        assertThat(root.resolve("java/fr/backyard/config/SecurityConfig.java"))
            .as("racine des sources de production introuvable : %s", root)
            .isRegularFile();
        return root;
    }

    /** Occurrences « fichier:ligne: texte » des motifs interdits dans un contenu de fichier. */
    static List<String> forbiddenOccurrences(String fileName, String content) {
        List<String> occurrences = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            for (Pattern forbidden : FORBIDDEN_APIS) {
                Matcher matcher = forbidden.matcher(lines[index]);
                if (matcher.find()) {
                    occurrences.add(fileName + ":" + (index + 1) + ": " + lines[index].trim());
                }
            }
        }
        return occurrences;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("lecture impossible de " + file, e);
        }
    }

    @Test
    @DisplayName("CA56 - le bean PasswordEncoder effectif du contexte est un BCryptPasswordEncoder operationnel")
    void ca56_effectivePasswordEncoderIsBCrypt() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PasswordEncoder.class);
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);

            assertThat(encoder).isExactlyInstanceOf(BCryptPasswordEncoder.class);
            assertThat(encoder.encode("admin-secret")).startsWith("$2").isNotEqualTo("admin-secret");
            assertThat(encoder.matches("admin-secret", ADMIN_HASH_FOR_ADMIN_SECRET)).isTrue();
            assertThat(encoder.matches("admin-secret", "admin-secret"))
                .as("un mot de passe en clair ne doit jamais etre accepte comme hash").isFalse();
        });
    }

    @Test
    @DisplayName("CA56 - aucune occurrence de WebSecurityConfigurerAdapter, .and(), antMatchers, mvcMatchers, NoOpPasswordEncoder dans src/main")
    void ca56_noForbiddenSecurityApiInProductionSources() throws IOException {
        Path root = productionSourceRoot();
        List<String> occurrences = new ArrayList<>();
        int scannedFiles = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                scannedFiles++;
                occurrences.addAll(forbiddenOccurrences(root.relativize(file).toString(), read(file)));
            }
        }

        assertThat(scannedFiles).as("fichiers analyses sous %s", root).isGreaterThan(10);
        assertThat(occurrences).as("API interdites par CA56 trouvees dans src/main").isEmpty();
    }

    @Test
    @DisplayName("CA56 - le detecteur d'API interdites repere chacun des motifs (garde-fou contre un test vide)")
    void ca56_detectorFlagsEachForbiddenPattern() {
        String offending = String.join("\n",
            "class Legacy extends WebSecurityConfigurerAdapter {",
            "  http.authorizeRequests().antMatchers(\"/a\").permitAll()",
            "      .and()",
            "      .mvcMatchers(\"/b\");",
            "  PasswordEncoder e = NoOpPasswordEncoder.getInstance();",
            "}");
        String compliant = String.join("\n",
            ".requestMatchers(\"/api/admin/**\").hasRole(ROLE_ADMIN)",
            "spec.and(other); // Specification.and avec argument : hors DSL de securite",
            "return new BCryptPasswordEncoder();");

        assertThat(forbiddenOccurrences("Legacy.java", offending))
            .containsExactly(
                "Legacy.java:1: class Legacy extends WebSecurityConfigurerAdapter {",
                "Legacy.java:2: http.authorizeRequests().antMatchers(\"/a\").permitAll()",
                "Legacy.java:3: .and()",
                "Legacy.java:4: .mvcMatchers(\"/b\");",
                "Legacy.java:5: PasswordEncoder e = NoOpPasswordEncoder.getInstance();");
        assertThat(forbiddenOccurrences("Ok.java", compliant)).isEmpty();
    }
}
