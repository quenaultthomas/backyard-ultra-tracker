package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.base.DescribedPredicate;

/**
 * CA5 (2.4) - placement et dépendances de l'affectation des bénévoles. Seule exception : la classe nommée
 * {@code AnnuaireBenevolesAdapter} du package d'assemblage {@code fr.backyard.tracker.assemblage}, qui voit à la fois
 * courses.domaine et comptes. Aucun motif générique : toute autre classe de courses qui référence comptes fait échouer.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class AffectationBenevolesArchitectureTest {

    private static final String COURSES = "fr.backyard.tracker.courses.";
    private static final String ASSEMBLAGE = "fr.backyard.tracker.assemblage..";
    private static final String ADAPTATEUR = "AnnuaireBenevolesAdapter";
    // 3.5 RG6 : seconde exception nommée, l'adaptateur de l'annuaire des pseudos
    private static final String ADAPTATEUR_PSEUDOS = "AnnuairePseudosAdapter";
    // 3.6 RG16 : troisième exception nommée, le pont d'annulation des inscriptions
    private static final String ADAPTATEUR_ANNULATION = "AnnulationInscriptionsAdapter";

    private static final DescribedPredicate<JavaClass> HORS_ADAPTATEUR_NOMME =
            DescribedPredicate.describe(
                    "autre que " + ADAPTATEUR + ", " + ADAPTATEUR_PSEUDOS + " et " + ADAPTATEUR_ANNULATION,
                    c -> !c.getSimpleName().equals(ADAPTATEUR) && !c.getSimpleName().equals(ADAPTATEUR_PSEUDOS)
                            && !c.getSimpleName().equals(ADAPTATEUR_ANNULATION));

    @ArchTest
    static void ca5_le_port_et_les_exceptions_sont_dans_le_domaine_courses(JavaClasses classes) {
        classes().that().haveSimpleName("AnnuaireBenevoles")
                .or().haveSimpleName("CourseTermineeException").or().haveSimpleName("BenevoleInconnuException")
                .should().resideInAPackage(COURSES + "domaine..")
                .as("CA5 : AnnuaireBenevoles et les exceptions d'affectation sont dans courses.domaine")
                .check(classes);
    }

    @ArchTest
    static void ca5_le_domaine_courses_reste_pur(JavaClasses classes) {
        classes().that().resideInAPackage(COURSES + "domaine..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", COURSES + "domaine..")
                .as("CA5 : le domaine courses ne dépend ni de Spring, ni de JPA, ni de Jackson, ni de comptes")
                .check(classes);
    }

    @ArchTest
    static void ca5_les_cas_d_usage_d_affectation_sont_dans_application_sans_dto_ni_infrastructure(JavaClasses classes) {
        classes().that().haveSimpleName("AffecterBenevoles").or().haveSimpleName("LireFicheCourse")
                .or().haveSimpleName("ListerCoursesDuBenevole")
                .should().resideInAPackage(COURSES + "application..")
                .as("CA5 : les cas d'usage d'affectation sont dans courses.application")
                .check(classes);
        classes().that().haveSimpleName("AffecterBenevoles").or().haveSimpleName("LireFicheCourse")
                .or().haveSimpleName("ListerCoursesDuBenevole")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", COURSES + "domaine..", COURSES + "application..",
                        "org.springframework.stereotype..", "org.springframework.transaction..")
                .as("CA5 : ces cas d'usage ne dépendent que du domaine, de java, du stéréotype et de la transaction")
                .check(classes);
    }

    @ArchTest
    static void ca5_courses_ne_depend_pas_de_comptes(JavaClasses classes) {
        noClasses().that().resideInAPackage("..courses..")
                .should().dependOnClassesThat().resideInAPackage("..comptes..")
                .as("CA5 : aucune classe de courses ne référence comptes")
                .check(classes);
    }

    @ArchTest
    static void ca5_comptes_ne_depend_pas_de_courses(JavaClasses classes) {
        noClasses().that().resideInAPackage("..comptes..")
                .should().dependOnClassesThat().resideInAPackage("..courses..")
                .as("CA5 : aucune classe de comptes ne référence courses")
                .check(classes);
    }

    @ArchTest
    static void ca5_les_controleurs_courses_n_accedent_pas_a_l_infrastructure(JavaClasses classes) {
        classes().that().resideInAPackage(COURSES + "exposition..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage(COURSES + "infrastructure..")
                .as("CA5 : courses.exposition n'accède pas à courses.infrastructure")
                .check(classes);
    }

    @ArchTest
    static void ca5_l_adaptateur_de_l_annuaire_est_dans_l_assemblage_et_implemente_le_port(JavaClasses classes) {
        classes().that().haveSimpleName(ADAPTATEUR)
                .should().resideInAPackage(ASSEMBLAGE)
                .andShould().implement(COURSES + "domaine.AnnuaireBenevoles")
                .as("CA5 : l'adaptateur de l'annuaire est dans le package d'assemblage et implémente le port")
                .check(classes);
    }

    @ArchTest
    static void ca5_seul_l_adaptateur_nomme_est_exempte_dans_l_assemblage(JavaClasses classes) {
        noClasses().that().resideInAPackage(ASSEMBLAGE).and(HORS_ADAPTATEUR_NOMME)
                .should().dependOnClassesThat().resideInAnyPackage("..comptes..", "..courses..")
                .as("CA5 : aucune autre classe d'assemblage ne dépend de comptes ou de courses")
                .check(classes);
    }
}
