package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/** CA11 (4.8, RG19) - la règle d'abandon automatique est dans le domaine pur, le planificateur dans l'infrastructure. */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class AbandonAutomatiqueArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca11_la_regle_et_le_motif_sont_dans_le_domaine_pur(JavaClasses classes) {
        classes().that().haveSimpleName("AbandonAutomatique").or().haveSimpleName("MotifAbandon")
                .should().resideInAPackage(COURSES + "domaine..")
                .andShould().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..")
                .allowEmptyShould(false)
                .as("CA11 : AbandonAutomatique et MotifAbandon sont dans le domaine, sans Spring ni JPA")
                .check(classes);
    }

    @ArchTest
    static void ca11_le_planificateur_est_en_infrastructure_et_n_appelle_que_le_cas_d_usage(JavaClasses classes) {
        classes().that().haveSimpleName("PlanificateurAbandonAutomatique")
                .should().resideInAPackage(COURSES + "infrastructure..")
                .andShould().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.slf4j..",
                        "org.springframework..", COURSES + "application..", COURSES + "infrastructure..")
                .allowEmptyShould(false)
                .as("CA11 : le planificateur est en infrastructure et ne dépend que du cas d'usage (pas du domaine)")
                .check(classes);
    }

    @ArchTest
    static void ca11_le_cas_d_usage_est_dans_application_sans_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("AbandonnerCoureursHorsDelai")
                .should().resideInAPackage(COURSES + "application..")
                .andShould().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..",
                        COURSES + "application..", "org.springframework.stereotype..",
                        "org.springframework.transaction..")
                .allowEmptyShould(false)
                .as("CA11 : le cas d'usage ne dépend que du domaine, de java, du stéréotype et de la transaction")
                .check(classes);
    }

    @ArchTest
    static void ca11_l_exposition_ne_touche_pas_l_abandon_automatique_ni_l_infrastructure(JavaClasses classes) {
        noClasses().that().resideInAPackage(COURSES + "exposition..")
                .should().dependOnClassesThat().resideInAPackage(COURSES + "infrastructure..")
                .as("CA11 : le contrôleur de pilotage n'a aucune logique d'infrastructure")
                .check(classes);
        noClasses().that().resideInAPackage(COURSES + "exposition..")
                .should().dependOnClassesThat().haveSimpleName("AbandonAutomatique")
                .as("CA11 : la règle d'abandon n'est pas appelée depuis l'exposition")
                .check(classes);
    }
}
