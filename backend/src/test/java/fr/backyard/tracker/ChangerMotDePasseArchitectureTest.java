package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA8 - placement et dépendances des classes ajoutées en 1.6b (règles générales : ArchitectureTest). */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ChangerMotDePasseArchitectureTest {

    private static final String COMPTES = "fr.backyard.tracker.comptes.";

    @ArchTest
    static void ca8_le_cas_d_usage_est_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("ChangerMotDePasse")
                .should().resideInAPackage(COMPTES + "application..")
                .as("CA8 : ChangerMotDePasse est dans comptes.application")
                .check(classes);
    }

    @ArchTest
    static void ca8_le_cas_d_usage_ne_depend_ni_de_l_exposition_ni_de_l_infrastructure_ni_de_spring_web(
            JavaClasses classes) {
        noClasses().that().haveSimpleName("ChangerMotDePasse")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..exposition..", "..infrastructure..", "org.springframework.web..",
                        "org.springframework.http..", "org.springframework.security..", "jakarta.servlet..")
                .as("CA8 : ChangerMotDePasse n'a aucune dépendance vers un DTO, le web, les sessions ou l'infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca8_le_port_d_invalidation_des_sessions_est_dans_le_domaine(JavaClasses classes) {
        classes().that().haveSimpleName("InvalidationAutresSessions")
                .should().resideInAPackage(COMPTES + "domaine..")
                .andShould().beInterfaces()
                .as("CA8 : l'invalidation des autres sessions est un port du domaine")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_adaptateurs_du_port_d_invalidation_sont_dans_infrastructure(JavaClasses classes) {
        classes().that().implement(COMPTES + "domaine.InvalidationAutresSessions")
                .should().resideInAPackage(COMPTES + "infrastructure..")
                .as("CA8 : le registre de sessions et l'invalidation sont dans comptes.infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca8_le_domaine_comptes_reste_sans_spring_jpa_ni_jackson(JavaClasses classes) {
        classes().that().resideInAPackage("..comptes.domaine..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "com.fasterxml..", "tools.jackson..")
                .as("CA8 : le domaine est sans Spring, JPA, servlet ni Jackson")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_controleurs_comptes_n_acces_pas_a_l_infrastructure(JavaClasses classes) {
        noClasses().that().resideInAPackage("..comptes.exposition..")
                .should().dependOnClassesThat().resideInAPackage("..comptes.infrastructure..")
                .as("CA8 : les contrôleurs n'accèdent pas à l'infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca8_courses_ne_depend_pas_de_comptes(JavaClasses classes) {
        noClasses().that().resideInAPackage("..courses..")
                .should().dependOnClassesThat().resideInAPackage("..comptes..")
                .as("CA8 : le contexte courses reste indépendant de comptes")
                .allowEmptyShould(true)
                .check(classes);
    }
}
