package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * CA6 (2.2) - les règles ArchUnit de 2.1a (CA9) s'appliquent aux nouvelles classes sans exception.
 * Les règles génériques de CoursesArchitectureTest (domaine pur, courses indépendant de comptes, exposition sans
 * infrastructure, couches connues) couvrent déjà toute classe ajoutée ; ce fichier cible ce qui est propre à 2.2.
 * Ces règles échouent tant que les classes n'existent pas (aucun test vide).
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ModifierCourseArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca6_modifier_course_est_dans_application(JavaClasses classes) {
        classes().that().haveSimpleName("ModifierCourse")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA6 : ModifierCourse est dans courses.application")
                .check(classes);
    }

    @ArchTest
    static void ca6_modifier_course_n_a_ni_dto_ni_spring_web_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("ModifierCourse")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA6 : ModifierCourse ne dépend que du domaine, de java, du stéréotype et de la transaction Spring")
                .check(classes);
    }

    @ArchTest
    static void ca6_les_exceptions_metier_de_modification_sont_dans_le_domaine_courses(JavaClasses classes) {
        classes().that().haveSimpleName("CourseIntrouvableException")
                .or().haveSimpleName("CourseNonModifiableException")
                .should().resideInAPackage(COURSES + "domaine..")
                .andShould().beAssignableTo(RuntimeException.class)
                .as("CA6 : CourseIntrouvableException et CourseNonModifiableException sont des exceptions métier du domaine courses")
                .check(classes);
    }
}
