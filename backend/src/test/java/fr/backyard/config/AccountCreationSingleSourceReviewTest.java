package fr.backyard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 7, RG5 (« aucune regle de format, de normalisation ni de conflit n'est reecrite ») : la creation
 * d'un compte n'existe qu'a un seul endroit, {@code AccountService.create}. Lecture de sources en texte brut.
 *
 * <p>Regles verifiees : (1) le code de E26 existe (sinon le controle passerait a vide) et delegue a
 * {@code accountService.create(} ; (2) il ne touche ni {@code Pseudo}, ni {@code PasswordPolicy}, ni encodeur,
 * ni depot de comptes, ni {@code toLowerCase} ; (3) le texte du conflit « Pseudo déjà utilisé » n'apparait que
 * dans {@code AccountService} ; (4) {@code passwordEncoder.encode} et {@code existsByPseudo} ne sont pas appeles
 * depuis les controleurs.
 */
@Tag("INC-7")
class AccountCreationSingleSourceReviewTest {

    private static final String E26_PATH = "/api/public/accounts";
    private static final String SERVICE_FILE = "AccountService.java";

    private static Path mainJava() {
        return Path.of(System.getProperty("basedir", "."), "src", "main", "java").toAbsolutePath().normalize();
    }

    private static List<Path> javaSources() {
        Path root = mainJava();
        assertThat(root).as("repertoire des sources de production").isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(sources).as("aucune source lue : le controle passerait a vide").isNotEmpty();
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> declaringE26() {
        return javaSources().stream().filter(path -> read(path).contains(E26_PATH)).toList();
    }

    @Test
    @DisplayName("RG5 - le code de production declare E26 (POST /api/public/accounts) dans un seul fichier, qui est un controleur")
    void rg5_e26IsDeclaredOnceInAController() {
        List<Path> files = declaringE26();

        assertThat(files).hasSize(1);
        String source = read(files.get(0));
        assertThat(files.get(0).getParent().getFileName().toString()).isEqualTo("api");
        assertThat(source).contains("@RestController");
    }

    @Test
    @DisplayName("RG5 - E26 delegue a accountService.create et ne reecrit ni format, ni normalisation, ni conflit, ni hachage")
    void rg5_e26DelegatesToAccountService() {
        String source = read(declaringE26().get(0));

        assertThat(source).contains("accountService.create(");
        assertThat(source)
            .doesNotContain("Pseudo.")
            .doesNotContain("PasswordPolicy")
            .doesNotContain("PasswordEncoder")
            .doesNotContain("AccountRepository")
            .doesNotContain("toLowerCase")
            .doesNotContain("BusinessConflictException")
            .doesNotContain("existsByPseudo")
            .doesNotContain(".encode(");
    }

    @Test
    @DisplayName("RG5 - le message de conflit « Pseudo déjà utilisé » n'est ecrit que dans AccountService")
    void rg5_conflictMessageHasASingleSource() {
        List<String> owners = javaSources().stream()
            .filter(path -> read(path).contains("Pseudo déjà utilisé"))
            .map(path -> path.getFileName().toString())
            .toList();

        assertThat(owners).containsExactly(SERVICE_FILE);
    }

    @Test
    @DisplayName("RG5 - aucun controleur n'appelle le depot de comptes ni l'encodeur de mots de passe")
    void rg5_controllersNeverTouchTheRepositoryNorTheEncoder() {
        List<Path> controllers = javaSources().stream()
            .filter(path -> path.getParent().getFileName().toString().equals("api"))
            .toList();
        assertThat(controllers).isNotEmpty();

        for (Path controller : controllers) {
            assertThat(read(controller)).as(controller.getFileName().toString())
                .doesNotContain("accountRepository")
                .doesNotContain("passwordEncoder")
                .doesNotContain("existsByPseudo");
        }
    }
}
