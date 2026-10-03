package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA3 (2.5) - placement et dépendances de la suppression d'une Course, sans exception supplémentaire (RG9). */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class SuppressionCourseArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca3_l_exception_est_dans_le_domaine_courses(JavaClasses classes) {
        classes().that().haveSimpleName("CourseNonSupprimableException")
                .should().resideInAPackage(COURSES + "domaine..")
                .as("CA3 : CourseNonSupprimableException est dans courses.domaine")
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
    static void ca3_le_cas_d_usage_est_dans_application_sans_dto_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("SupprimerCourse")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA3 : SupprimerCourse est dans courses.application")
                .check(classes);
        classes().that().haveSimpleName("SupprimerCourse")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA3 : SupprimerCourse ne dépend que du domaine, de java, du stéréotype et de la transaction")
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
