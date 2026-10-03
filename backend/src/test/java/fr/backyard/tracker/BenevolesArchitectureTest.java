package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA8 - placement et dépendances des classes ajoutées en 1.6a (les règles générales sont dans ArchitectureTest, CA9). */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class BenevolesArchitectureTest {

    private static final String COMPTES = "fr.backyard.tracker.comptes.";

    @ArchTest
    static void ca8_les_cas_d_usage_benevoles_sont_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("CreerBenevole").or().haveSimpleName("ListerBenevoles")
                .should().resideInAPackage(COMPTES + "application..")
                .as("CA8 : CreerBenevole et ListerBenevoles sont dans comptes.application")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_cas_d_usage_benevoles_ne_dependent_ni_de_l_exposition_ni_de_spring_web(JavaClasses classes) {
        noClasses().that().haveSimpleName("CreerBenevole").or().haveSimpleName("ListerBenevoles")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..exposition..", "..infrastructure..", "org.springframework.web..",
                        "org.springframework.http..", "org.springframework.security..")
                .as("CA8 : les cas d'usage benevoles n'ont aucune dépendance vers un DTO, le web ou l'infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca8_le_domaine_comptes_reste_sans_spring_jpa_ni_jackson(JavaClasses classes) {
        classes().that().resideInAPackage("..comptes.domaine..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "org.springframework..", "jakarta.persistence..", "com.fasterxml..", "tools.jackson..")
                .as("CA8 : le domaine est sans Spring, JPA ni Jackson")
                .check(classes);
    }

    @ArchTest
    static void ca8_courses_ne_depend_pas_de_comptes_benevoles(JavaClasses classes) {
        noClasses().that().resideInAPackage("..courses..")
                .should().dependOnClassesThat().resideInAPackage("..comptes..")
                .as("CA8 : le contexte courses reste indépendant de comptes")
                .allowEmptyShould(true)
                .check(classes);
    }
}
