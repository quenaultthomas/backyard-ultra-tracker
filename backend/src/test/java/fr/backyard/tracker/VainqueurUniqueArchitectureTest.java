package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * CA10 (4.9, RG6, RG15) - la désignation du vainqueur et la fin de Course ne sont déclenchées que par le cas
 * d'usage de contrôle (couche application) : ni l'exposition, ni l'infrastructure (planificateur compris) ne les
 * appellent, et la règle de décision n'est pas dupliquée hors du domaine.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class VainqueurUniqueArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";

    @ArchTest
    static void ca10_exposition_et_infrastructure_ne_declenchent_ni_victoire_ni_fin_de_course(JavaClasses classes) {
        noClasses().that().resideInAnyPackage(COURSES + "exposition..", COURSES + "infrastructure..")
                .should().callMethod(COURSES + "domaine.Inscription", "devenirVainqueur")
                .orShould().callMethod(COURSES + "domaine.Course", "terminer")
                .as("CA10 : seuls le domaine et le cas d'usage de contrôle déclenchent une victoire ou une fin de Course")
                .check(classes);
    }

    @ArchTest
    static void ca10_la_regle_de_decision_n_est_appelee_que_par_le_cas_d_usage(JavaClasses classes) {
        noClasses().that().resideOutsideOfPackages(COURSES + "domaine..", COURSES + "application..")
                .should().dependOnClassesThat().haveSimpleName("AbandonAutomatique")
                .as("CA10 : la règle de contrôle de fin de Boucle n'est utilisée que depuis l'application")
                .check(classes);
    }
}
