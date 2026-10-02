package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA11 - placement des classes ajoutées en 1.4 (les règles générales sont dans ArchitectureTest). */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class AdminMasterArchitectureTest {

    private static final String COMPTES = "fr.backyard.tracker.comptes.";

    @ArchTest
    static void ca11_initialiser_admin_master_est_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("InitialiserAdminMaster")
                .should().resideInAPackage(COMPTES + "application..")
                .as("CA11 : InitialiserAdminMaster est dans comptes.application")
                .check(classes);
    }

    @ArchTest
    static void ca11_les_application_runner_sont_dans_infrastructure(JavaClasses classes) {
        classes().that().implement("org.springframework.boot.ApplicationRunner")
                .should().resideInAPackage("..infrastructure..")
                .as("CA11 : les ApplicationRunner sont dans l'infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca11_les_proprietes_admin_master_sont_dans_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("AdminMasterProprietes")
                .should().resideInAPackage(COMPTES + "infrastructure..")
                .as("CA11 : les propriétés de l'admin master sont dans comptes.infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca11_le_domaine_ne_depend_pas_de_spring_jpa_ni_jackson(JavaClasses classes) {
        classes().that().resideInAPackage("..comptes.domaine..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "org.springframework..", "jakarta.persistence..", "com.fasterxml..", "tools.jackson..")
                .as("CA11 : le domaine est sans Spring, JPA ni Jackson")
                .check(classes);
    }
}
