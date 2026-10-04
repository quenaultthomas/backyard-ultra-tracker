package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA3 (3.1) - placement et dépendances de l'inscription à une Course, sans exception supplémentaire (RG13). */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class InscriptionArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca3_les_types_de_l_inscription_sont_dans_le_domaine_courses(JavaClasses classes) {
        classes().that().haveSimpleName("Inscription")
                .or().haveSimpleName("JetonQr")
                .or().haveSimpleName("InscriptionDejaExistanteException")
                .or().haveSimpleName("DepotInscriptions")
                .or().haveSimpleName("GenerateurJetonQr")
                .should().resideInAPackage(COURSES + "domaine..")
                .as("CA3 : Inscription, JetonQr, l'exception et les ports sont dans courses.domaine")
                .check(classes);
    }

    @ArchTest
    static void ca3_le_domaine_courses_reste_pur(JavaClasses classes) {
        classes().that().resideInAPackage(COURSES + "domaine..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..")
                .as("CA3 : le domaine courses ne dépend ni de Spring, ni de JPA, ni de Jackson")
                .check(classes);
    }

    @ArchTest
    static void ca3_les_cas_d_usage_sont_dans_application_sans_dto_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("InscrireCoureur").or().haveSimpleName("ListerCoursesOuvertes")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA3 : InscrireCoureur et ListerCoursesOuvertes sont dans courses.application")
                .check(classes);
        classes().that().haveSimpleName("InscrireCoureur").or().haveSimpleName("ListerCoursesOuvertes")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA3 : les cas d'usage d'inscription ne dépendent que du domaine, de java, du stéréotype et de la transaction")
                .check(classes);
    }

    @ArchTest
    static void ca3_courses_ne_depend_pas_de_comptes(JavaClasses classes) {
        noClasses().that().resideInAPackage("..courses..")
                .should().dependOnClassesThat().resideInAPackage("..comptes..")
                .as("CA3 : courses est indépendant de comptes")
                .check(classes);
    }

    @ArchTest
    static void ca3_les_controleurs_courses_n_accedent_pas_a_l_infrastructure(JavaClasses classes) {
        noClasses().that().resideInAPackage(COURSES + "exposition..")
                .should().dependOnClassesThat().resideInAPackage(COURSES + "infrastructure..")
                .as("CA3 : courses.exposition n'accède pas à courses.infrastructure")
                .check(classes);
    }
}
