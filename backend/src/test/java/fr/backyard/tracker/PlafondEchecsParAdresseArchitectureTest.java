package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * Incrément 8.4, CA11 (RG16) : placement du plafond d'échecs par adresse dans les couches, sans nouvelle exception
 * aux règles existantes. Test unitaire sans Spring.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class PlafondEchecsParAdresseArchitectureTest {

    private static final String DOMAINE = "fr.backyard.tracker.comptes.domaine";

    @ArchTest
    static void ca11_les_types_du_plafond_et_ses_ports_sont_dans_le_domaine_des_comptes(JavaClasses classes) {
        classes().that().haveSimpleName("PlafondEchecsParAdresse")
                .or().haveSimpleName("PlafondEchecsParAdresseInvalideException")
                .or().haveSimpleName("EchecsParAdresse")
                .or().haveSimpleName("AdresseSuivie")
                .or().haveSimpleName("RegistreEchecsParAdresse")
                .or().haveSimpleName("AdresseDeLaRequete")
                .should().resideInAPackage(DOMAINE)
                .allowEmptyShould(false)
                .check(classes);
    }

    @ArchTest
    static void ca11_le_domaine_des_comptes_ne_depend_d_aucun_framework(JavaClasses classes) {
        noClasses().that().resideInAPackage(DOMAINE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "com.fasterxml..", "tools.jackson..", "org.hibernate..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @ArchTest
    static void ca11_les_cas_d_usage_des_comptes_ne_dependent_ni_de_l_infrastructure_ni_de_l_adresse_cliente(
            JavaClasses classes) {
        noClasses().that().resideInAPackage("fr.backyard.tracker.comptes.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "fr.backyard.tracker.comptes.infrastructure..", "fr.backyard.tracker.partage.infrastructure..")
                .allowEmptyShould(false)
                .check(classes);
    }

    @ArchTest
    static void ca11_adresse_cliente_n_est_referencee_que_par_les_infrastructures_comptes_et_courses(
            JavaClasses classes) {
        noClasses().that().resideOutsideOfPackages("fr.backyard.tracker.comptes.infrastructure..",
                        "fr.backyard.tracker.courses.infrastructure..", "fr.backyard.tracker.partage.infrastructure..")
                .should().dependOnClassesThat().haveSimpleName("AdresseCliente")
                .allowEmptyShould(true)
                .check(classes);
    }
}
