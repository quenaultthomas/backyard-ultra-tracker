package fr.backyard.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 3 - CA45, partie « revue de code » non couverte par {@link ApiArchitectureTest}
 * (reserve R3-1, accord fonctionnel du 2026-09-26) : aucune condition sur un statut, un yard, un dossard
 * ou un role dans {@code fr.backyard.api}. JUnit pur, sans ArchUnit : analyse textuelle des sources.
 *
 * <p>Methode : chaque fichier est debarrasse de ses commentaires, puis decoupe en fragments aux
 * separateurs {@code ;}, <code>{</code> et <code>}</code> (une condition sur plusieurs lignes reste
 * dans un seul fragment). Un fragment qui contient une construction conditionnelle ne doit citer aucun
 * terme metier (statut, yard, dossard, role).
 */
@Tag("INC-3")
@Tag("INC3-CA45")
class ApiSourceReviewTest {

    private static final Pattern CONDITIONAL_CONSTRUCT = Pattern.compile(
        "\\b(if|while|switch|case)\\b|&&|\\|\\||==|!=|\\.equals(IgnoreCase)?\\(|\\.filter\\("
            + "|\\.(any|all|none)Match\\(|\\s\\?\\s(?!extends\\b|super\\b)");

    private static final List<Pattern> BUSINESS_TERMS = List.of(
        // statut (types et constantes des enums du domaine)
        Pattern.compile("\\b(RaceStatus|RunnerStatus|DnfReason|PassageSource)\\b"),
        Pattern.compile("\\b(SETUP|RUNNING|FINISHED|ACTIVE|DNF|WINNER|TIMEOUT|VOLUNTARY|MANUAL|OTHER)\\b"),
        // yard et dossard
        Pattern.compile("(?i)yard"),
        Pattern.compile("(?i)bib"),
        // role
        Pattern.compile("(?i)role|authorit|isUserInRole|\\bADMIN\\b|\\bSCANNER\\b"));

    private static Path apiSourceRoot() {
        Path root = Path.of(System.getProperty("basedir", "."), "src", "main", "java", "fr", "backyard", "api")
            .toAbsolutePath().normalize();
        assertThat(root.resolve("AdminRunnerController.java"))
            .as("sources du package fr.backyard.api introuvables : %s", root)
            .isRegularFile();
        return root;
    }

    /** Retire les commentaires ({@code //} et bloc) en conservant les litteraux chaine et caractere. */
    static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                code.append(' ');
            } else if (current == '"' || current == '\'') {
                int end = endOfLiteral(source, index, current);
                code.append(source, index, end);
                index = end;
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    private static int endOfLiteral(String source, int start, char quote) {
        int index = start + 1;
        while (index < source.length() && source.charAt(index) != quote) {
            index += source.charAt(index) == '\\' ? 2 : 1;
        }
        return Math.min(index + 1, source.length());
    }

    /** Fragments conditionnels citant un terme metier, normalises sur une ligne. */
    static List<String> businessConditions(String fileName, String source) {
        List<String> findings = new ArrayList<>();
        for (String fragment : withoutComments(source).split("[;{}]")) {
            String normalized = fragment.replaceAll("\\s+", " ").trim();
            if (CONDITIONAL_CONSTRUCT.matcher(" " + normalized + " ").find()
                && BUSINESS_TERMS.stream().anyMatch(term -> term.matcher(normalized).find())) {
                findings.add(fileName + ": " + normalized);
            }
        }
        return findings;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("lecture impossible de " + file, e);
        }
    }

    @Test
    @DisplayName("CA45 - aucune condition sur un statut, un yard, un dossard ou un role dans fr.backyard.api")
    void ca45_noConditionOnStatusYardBibOrRoleInApiPackage() throws IOException {
        Path root = apiSourceRoot();
        List<String> findings = new ArrayList<>();
        int scannedFiles = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                scannedFiles++;
                findings.addAll(businessConditions(root.relativize(file).toString(), read(file)));
            }
        }

        assertThat(scannedFiles).as("fichiers Java analyses sous %s", root).isGreaterThan(10);
        assertThat(findings).as("conditions metier trouvees dans fr.backyard.api").isEmpty();
    }

    @Test
    @DisplayName("CA45 - le detecteur repere une condition sur statut, yard, dossard ou role, y compris sur plusieurs lignes")
    void ca45_detectorFlagsBusinessConditions() {
        String offending = String.join("\n",
            "if (runner.getStatus() == RunnerStatus.DNF) { return conflict(); }",
            "int yard = request.currentYard() > 3",
            "    ? 1 : 0;",
            "boolean taken = runners.stream().anyMatch(r -> r.getBib() == request.bib());",
            "if (request.isUserInRole(\"ADMIN\")) { return all(); }",
            // l'en-tete du switch ne cite pas de terme metier, mais chacun de ses case le fait
            "switch (race.status()) { case RUNNING: break; }");

        assertThat(businessConditions("Offending.java", offending))
            .containsExactly(
                "Offending.java: if (runner.getStatus() == RunnerStatus.DNF)",
                "Offending.java: int yard = request.currentYard() > 3 ? 1 : 0",
                "Offending.java: boolean taken = runners.stream().anyMatch(r -> r.getBib() == request.bib())",
                "Offending.java: if (request.isUserInRole(\"ADMIN\"))",
                "Offending.java: case RUNNING: break");
    }

    @Test
    @DisplayName("CA45 - le detecteur ignore les commentaires, les mappings sans condition et les conditions techniques")
    void ca45_detectorIgnoresCommentsAndTechnicalConditions() {
        String compliant = String.join("\n",
            "// if (runner.getStatus() == RunnerStatus.DNF) : commentaire, pas du code",
            "/* switch sur le yard : commentaire bloc */",
            "return new RunnerBoardEntryResponse(entry.bib(), entry.status().name(), entry.currentYard());",
            "if (headers != null) { copy(headers); }",
            "String url = \"http://example//path\";",
            "Map<String, ? extends Object> values = Map.of();");

        assertThat(businessConditions("Compliant.java", compliant)).isEmpty();
    }
}
