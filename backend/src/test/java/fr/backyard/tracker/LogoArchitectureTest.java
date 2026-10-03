package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * CA8 (2.3) - les règles ArchUnit de 2.1a (CA9) s'appliquent aux classes du logo sans exception.
 * Les règles génériques de CoursesArchitectureTest (domaine pur, courses indépendant de comptes, exposition sans
 * infrastructure, couches connues) couvrent déjà toute classe ajoutée ; ce fichier cible ce qui est propre à 2.3.
 * Ces règles échouent tant que les classes n'existent pas (aucun test vide).
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class LogoArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca8_logo_format_et_depot_sont_dans_le_domaine_sans_spring_jpa_ni_jackson(JavaClasses classes) {
        classes().that().haveSimpleName("Logo")
                .or().haveSimpleName("FormatLogo")
                .or().haveSimpleName("DepotLogos")
                .should().resideInAPackage(COURSES + "domaine..")
                .andShould().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..")
                .as("CA8 : Logo, FormatLogo et DepotLogos sont dans courses.domaine, sans Spring, JPA ni Jackson")
                .check(classes);
    }

    @ArchTest
    static void ca8_depot_logos_est_une_interface(JavaClasses classes) {
        classes().that().haveSimpleName("DepotLogos")
                .should().beInterfaces()
                .as("CA8 : DepotLogos est un port (interface)")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_exceptions_de_logo_sont_des_exceptions_metier_du_domaine(JavaClasses classes) {
        classes().that().haveSimpleName("LogoInvalideException")
                .or().haveSimpleName("LogoIntrouvableException")
                .should().resideInAPackage(COURSES + "domaine..")
                .andShould().beAssignableTo(RuntimeException.class)
                .as("CA8 : les exceptions de logo sont des exceptions métier du domaine courses")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_cas_d_usage_de_logo_sont_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("EnregistrerLogo")
                .or().haveSimpleName("SupprimerLogo")
                .or().haveSimpleName("LireLogo")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA8 : EnregistrerLogo, SupprimerLogo et LireLogo sont dans courses.application")
                .check(classes);
    }

    @ArchTest
    static void ca8_les_cas_d_usage_de_logo_n_ont_ni_dto_ni_spring_web_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("EnregistrerLogo")
                .or().haveSimpleName("SupprimerLogo")
                .or().haveSimpleName("LireLogo")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA8 : les cas d'usage de logo ne dépendent que du domaine, de java, du stéréotype et de la transaction Spring")
                .check(classes);
    }

    @ArchTest
    static void ca8_l_entite_et_l_adaptateur_jpa_du_logo_sont_dans_courses_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("LogoCourseJpaEntity")
                .or().haveSimpleName("LogoCourseJpaAdapter")
                .should().resideInAPackage(COURSES + "infrastructure..")
                .as("CA8 : LogoCourseJpaEntity et LogoCourseJpaAdapter sont dans courses.infrastructure")
                .check(classes);
    }
}
