package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * CA9 (2.1a) - placement et dépendances des classes du contexte courses.
 * Ces règles ne sont pas vides : elles échouent tant que les classes du contexte courses n'existent pas.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class CoursesArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca9_le_domaine_courses_ne_depend_que_de_java_et_de_son_propre_domaine(JavaClasses classes) {
        classes().that().resideInAPackage(COURSES + "domaine..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..")
                .as("CA9 : le domaine courses est pur (sans Spring, JPA ni Jackson)")
                .check(classes);
    }

    @ArchTest
    static void ca9_les_cas_d_usage_courses_sont_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("DeclarerCourse").or().haveSimpleName("ListerCourses")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA9 : DeclarerCourse et ListerCourses sont dans courses.application")
                .check(classes);
    }

    @ArchTest
    static void ca9_les_cas_d_usage_courses_n_ont_ni_dto_ni_spring_web_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("DeclarerCourse").or().haveSimpleName("ListerCourses")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA9 : les cas d'usage courses ne dépendent que du domaine, de java, du stéréotype et de la transaction Spring")
                .check(classes);
    }

    @ArchTest
    static void ca9_courses_ne_depend_pas_de_comptes(JavaClasses classes) {
        noClasses().that().resideInAPackage("..courses..")
                .should().dependOnClassesThat().resideInAPackage("..comptes..")
                .as("CA9 : courses est indépendant de comptes")
                .check(classes);
    }

    @ArchTest
    static void ca9_comptes_ne_depend_pas_de_courses(JavaClasses classes) {
        noClasses().that().resideInAPackage("..comptes..")
                .should().dependOnClassesThat().resideInAPackage("..courses..")
                .as("CA9 : comptes est indépendant de courses")
                .check(classes);
    }

    @ArchTest
    static void ca9_les_controleurs_courses_sont_dans_exposition_sans_acces_a_l_infrastructure(JavaClasses classes) {
        classes().that().resideInAPackage(COURSES + "exposition..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage(COURSES + "infrastructure..")
                .as("CA9 : courses.exposition n'accède pas à courses.infrastructure")
                .check(classes);
        classes().that().haveSimpleNameEndingWith("Controller").and().resideInAPackage("fr.backyard.tracker.courses..")
                .should().resideInAPackage(COURSES + "exposition..")
                .as("CA9 : les contrôleurs courses sont dans courses.exposition")
                .check(classes);
    }

    @ArchTest
    static void ca9_l_entite_jpa_course_est_dans_courses_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("CourseJpaEntity")
                .should().resideInAPackage(COURSES + "infrastructure..")
                .as("CA9 : CourseJpaEntity est dans courses.infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca9_toute_classe_courses_est_dans_une_couche_connue(JavaClasses classes) {
        classes().that().resideInAPackage("fr.backyard.tracker.courses..")
                .should().resideInAnyPackage(
                        COURSES + "domaine..", COURSES + "application..",
                        COURSES + "infrastructure..", COURSES + "exposition..")
                .as("CA9 : toute classe de courses est dans domaine, application, infrastructure ou exposition")
                .check(classes);
    }
}
