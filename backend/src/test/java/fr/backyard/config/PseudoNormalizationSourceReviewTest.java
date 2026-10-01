package fr.backyard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec increment 6 - RG9, CA13 (reserve R5-5 de l'inc. 5) : la normalisation du pseudo n'existe qu'a un seul
 * endroit, {@code fr/backyard/domain/Pseudo.java}. Lecture de sources en texte brut, commentaires compris.
 *
 * <p>Regle verifiee : {@code toLowerCase} apparait exactement une fois dans tout le code de production, dans
 * {@code Pseudo.java} ; aucune occurrence (casse ignoree) de {@code lower(}, {@code upper(}, {@code IgnoreCase}
 * ni {@code ILIKE}. Le test est demontre discriminant par des sources synthetiques, et ne peut pas passer a
 * vide : repertoire introuvable ou aucun fichier lu = echec.
 */
@Tag("INC-6")
@Tag("INC6-CA13")
class PseudoNormalizationSourceReviewTest {

    private static final String PSEUDO_FILE = "fr/backyard/domain/Pseudo.java";
    private static final String TO_LOWER_CASE = "toLowerCase";
    private static final Pattern FORBIDDEN = Pattern.compile("lower\\(|upper\\(|IgnoreCase|ILIKE",
        Pattern.CASE_INSENSITIVE);
    private static final List<String> RESOURCE_EXTENSIONS = List.of(".sql", ".properties", ".yml", ".yaml", ".xml",
        ".json", ".conf");

    // ----- la regle, appliquee a un ensemble de sources (cle = chemin relatif, texte brut) -----

    /** Liste des violations de RG9 ; vide si la regle est respectee. Echoue si aucune source n'est fournie. */
    static List<String> violations(Map<String, String> sources) {
        assertThat(sources).as("aucune source fournie : le controle passerait a vide").isNotEmpty();
        List<String> violations = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            int count = occurrences(source.getValue(), TO_LOWER_CASE);
            total += count;
            if (count > 0 && !PSEUDO_FILE.equals(source.getKey())) {
                violations.add(TO_LOWER_CASE + " hors de " + PSEUDO_FILE + " : " + source.getKey());
            }
            Matcher forbidden = FORBIDDEN.matcher(source.getValue());
            while (forbidden.find()) {
                violations.add("« " + forbidden.group() + " » interdit dans " + source.getKey());
            }
        }
        if (total != 1) {
            violations.add(TO_LOWER_CASE + " doit apparaitre exactement 1 fois, trouve " + total);
        }
        return violations;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    /** Lit les .java de {@code javaRoot} et les ressources de {@code resourcesRoot}, chemins relatifs a chaque racine. */
    static Map<String, String> readSources(Path javaRoot, Path resourcesRoot) {
        Map<String, String> sources = new LinkedHashMap<>();
        readTree(javaRoot, ".java", List.of(".java"), sources);
        readTree(resourcesRoot, "resources", RESOURCE_EXTENSIONS, sources);
        assertThat(sources).as("aucun fichier source lu sous %s et %s", javaRoot, resourcesRoot).isNotEmpty();
        return sources;
    }

    private static void readTree(Path root, String label, List<String> extensions, Map<String, String> into) {
        assertThat(root).as("repertoire source introuvable (%s) : %s", label, root).isDirectory();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (extensions.stream().anyMatch(name::endsWith)) {
                    into.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible sous " + root, e);
        }
    }

    private static Path mainRoot(String child) {
        return Path.of(System.getProperty("basedir", "."), "src", "main", child).toAbsolutePath().normalize();
    }

    // ----- le test sur les vraies sources -----

    @Test
    @DisplayName("CA13 - sources de production : toLowerCase une seule fois (Pseudo.java), aucun lower( upper( IgnoreCase ILIKE")
    void ca13_productionSourcesRespectTheNormalizationRule() {
        Map<String, String> sources = readSources(mainRoot("java"), mainRoot("resources"));

        assertThat(sources).as("la source de reference doit etre lue").containsKey(PSEUDO_FILE);
        assertThat(sources.keySet()).as("des fichiers de ressources doivent aussi etre lus")
            .anyMatch(path -> path.endsWith(".sql"));
        assertThat(violations(sources)).isEmpty();
    }

    // ----- le test est discriminant : sources synthetiques (a) a (e) -----

    private static final String PSEUDO_OK = "class Pseudo { String n(String s) { return s.toLowerCase(Locale.ROOT); } }";

    @Test
    @DisplayName("CA13 (e) - une seule occurrence, dans Pseudo.java : conforme")
    void ca13_e_referenceSourcesPass() {
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK, "fr/backyard/Autre.java", "class Autre { }"))).isEmpty();
    }

    @Test
    @DisplayName("CA13 (a) - deux occurrences dans deux fichiers : echec")
    void ca13_a_twoOccurrencesInTwoFilesFail() {
        Map<String, String> sources = Map.of(PSEUDO_FILE, PSEUDO_OK,
            "fr/backyard/service/Autre.java", "class Autre { String n(String s) { return s.toLowerCase(); } }");

        assertThat(violations(sources)).isNotEmpty()
            .anyMatch(v -> v.contains("exactement 1 fois, trouve 2"))
            .anyMatch(v -> v.contains("hors de") && v.contains("service/Autre.java"));
    }

    @Test
    @DisplayName("CA13 (b) - une occurrence dans un fichier autre que Pseudo.java : echec")
    void ca13_b_occurrenceOutsidePseudoFails() {
        Map<String, String> sources = Map.of(PSEUDO_FILE, "class Pseudo { }",
            "fr/backyard/service/Autre.java", "class Autre { String n(String s) { return s.toLowerCase(); } }");

        assertThat(violations(sources)).anyMatch(v -> v.contains("hors de") && v.contains("service/Autre.java"));
    }

    @Test
    @DisplayName("CA13 (c) - findByPseudoIgnoreCase : echec")
    void ca13_c_ignoreCaseQueryFails() {
        Map<String, String> sources = Map.of(PSEUDO_FILE, PSEUDO_OK,
            "fr/backyard/persistence/AccountRepository.java",
            "interface AccountRepository { Optional<Account> findByPseudoIgnoreCase(String p); }");

        assertThat(violations(sources)).anyMatch(v -> v.contains("IgnoreCase"));
    }

    @Test
    @DisplayName("CA13 (d) - LOWER(pseudo) dans une requete : echec")
    void ca13_d_lowerInQueryFails() {
        Map<String, String> sources = Map.of(PSEUDO_FILE, PSEUDO_OK,
            "fr/backyard/persistence/AccountRepository.java",
            "@Query(\"select a from Account a where LOWER(a.pseudo) = :p\") Account find(String p);");

        assertThat(violations(sources)).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("lower("));
    }

    @Test
    @DisplayName("CA13 - upper(, ILIKE (casse ignoree), dans un .sql comme dans un .java : echec")
    void ca13_upperAndIlikeFailWhateverTheCaseAndFileKind() {
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK, "db/migration/V9.sql",
            "select * from account where Upper(pseudo) = 'X'"))).anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("upper("));
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK, "db/migration/V9.sql",
            "select * from account where pseudo ilike 'x%'"))).anyMatch(v -> v.contains("ilike"));
    }

    @Test
    @DisplayName("CA13 - texte brut, commentaires compris : une occurrence en commentaire est comptee")
    void ca13_commentsAreCounted() {
        Map<String, String> sources = Map.of(PSEUDO_FILE, PSEUDO_OK,
            "fr/backyard/service/Autre.java", "// ne pas appeler toLowerCase ici\nclass Autre { }");

        assertThat(violations(sources)).anyMatch(v -> v.contains("hors de"));
    }

    @Test
    @DisplayName("CA13 - zero occurrence (Pseudo.java ne normalise plus) ou deux dans Pseudo.java : echec")
    void ca13_exactlyOneOccurrenceIsRequired() {
        assertThat(violations(Map.of(PSEUDO_FILE, "class Pseudo { }"))).anyMatch(v -> v.contains("trouve 0"));
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK + "\n" + PSEUDO_OK))).anyMatch(v -> v.contains("trouve 2"));
    }

    @Test
    @DisplayName("CA13 - methodes d'autres noms voisines (toUpperCase, equalsIgnoreCase) : toUpperCase n'est pas interdit, equalsIgnoreCase l'est")
    void ca13_neighbouringNames() {
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK, "fr/backyard/X.java", "s.toUpperCase();"))).isEmpty();
        assertThat(violations(Map.of(PSEUDO_FILE, PSEUDO_OK, "fr/backyard/X.java", "a.equalsIgnoreCase(b);")))
            .isNotEmpty();
    }

    // ----- pas de passage a vide -----

    @Test
    @DisplayName("CA13 - repertoire source introuvable : echec (pas de passage a vide)")
    void ca13_missingDirectoryFails(@TempDir Path temp) {
        assertThatThrownBy(() -> readSources(temp.resolve("absent"), temp)).isInstanceOf(AssertionError.class)
            .hasMessageContaining("introuvable");
    }

    @Test
    @DisplayName("CA13 - aucun fichier lu : echec (pas de passage a vide)")
    void ca13_noFileReadFails(@TempDir Path temp) throws IOException {
        Path java = Files.createDirectories(temp.resolve("java"));
        Path resources = Files.createDirectories(temp.resolve("resources"));
        Files.writeString(resources.resolve("notes.txt"), "ignore");

        assertThatThrownBy(() -> readSources(java, resources)).isInstanceOf(AssertionError.class)
            .hasMessageContaining("aucun fichier source lu");
        assertThatThrownBy(() -> violations(Map.of())).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("CA13 - la lecture releve .java et .sql avec des chemins relatifs normalises")
    void ca13_readSourcesKeepsRelativePaths(@TempDir Path temp) throws IOException {
        Path java = Files.createDirectories(temp.resolve("java/fr/backyard/domain"));
        Files.writeString(java.resolve("Pseudo.java"), PSEUDO_OK);
        Path resources = Files.createDirectories(temp.resolve("resources/db/migration"));
        Files.writeString(resources.resolve("V1__init.sql"), "create table t(id int);");

        Map<String, String> sources = readSources(temp.resolve("java"), temp.resolve("resources"));

        assertThat(sources).containsOnlyKeys(PSEUDO_FILE, "db/migration/V1__init.sql");
        assertThat(violations(sources)).isEmpty();
    }
}
