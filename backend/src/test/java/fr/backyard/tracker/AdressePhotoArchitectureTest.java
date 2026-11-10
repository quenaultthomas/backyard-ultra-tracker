package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, CA16 (RG12) : la route des photos n'existe plus dans le domaine ni l'application. Les
 * règles ArchUnit existantes ({@code ArchitectureTest}) ne sont ni modifiées ni assouplies : ces règles s'y ajoutent.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class AdressePhotoArchitectureTest {

    private static final String RACINE = "fr.backyard.tracker";

    @ArchTest
    static void ca16_partage_domaine_ne_contient_que_format_image(JavaClasses classes) {
        classes().that().resideInAPackage(RACINE + ".partage.domaine")
                .and().areTopLevelClasses()
                .should().haveSimpleName("FormatImage")
                .as("CA16 : partage.domaine ne contient que FormatImage (plus d'AdressePhoto)")
                .check(classes);
    }

    @ArchTest
    static void ca16_domaine_et_application_ne_dependent_pas_de_l_exposition(JavaClasses classes) {
        noClasses().that().resideInAnyPackage(RACINE + ".courses.domaine..", RACINE + ".courses.application..",
                        RACINE + ".comptes.domaine..", RACINE + ".comptes.application..")
                .should().dependOnClassesThat().resideInAnyPackage(RACINE + ".courses.exposition..",
                        RACINE + ".comptes.exposition..")
                .as("CA16 : domaine et application ne dépendent d'aucune classe d'exposition")
                .check(classes);
    }

    @ArchTest
    static void ca16_le_dto_du_suivi_ne_depend_pas_de_comptes(JavaClasses classes) {
        noClasses().that().haveSimpleNameStartingWith("SuiviCourseReponse")
                .should().dependOnClassesThat().resideInAPackage(RACINE + ".comptes..")
                .as("CA16 : SuiviCourseReponse ne dépend pas de comptes")
                .check(classes);
    }

    @Test
    @DisplayName("CA16 - aucune classe de domaine ou d'application ne contient la route /api/photos")
    void doit_ne_trouver_la_route_des_photos_ni_dans_le_domaine_ni_dans_l_application() throws IOException {
        Path sources = Path.of("src/main/java/fr/backyard/tracker");
        List<String> couches = List.of("courses/domaine", "courses/application", "comptes/domaine",
                "comptes/application", "partage/domaine");

        for (String couche : couches) {
            Path dossier = sources.resolve(couche);
            assertThat(dossier).as("dossier " + couche).isDirectory();
            try (Stream<Path> fichiers = Files.walk(dossier)) {
                for (Path fichier : fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(fichier);
                    // les commentaires peuvent citer la route (documentation) : seules les chaînes littérales comptent
                    assertThat(code).as(fichier.toString()).doesNotContain("\"/api/photos");
                }
            }
        }
    }
}
