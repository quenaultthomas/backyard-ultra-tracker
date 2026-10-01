package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Règles d'architecture (increment 0.3, RG9 à RG17). Test unitaire sans Spring.
 * Les packages étant vides au départ, les règles sont vides et s'activent
 * avec la première classe (archunit.properties, RG11). CA6 : toutes passent à vide.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String RACINE = "fr.backyard.tracker";
    private static final List<String> CONTEXTES = List.of("comptes", "courses");
    private static final List<String> COUCHES = List.of("domaine", "application", "infrastructure", "exposition");

    private static String paquet(String contexte, String couche) {
        return RACINE + "." + contexte + "." + couche + "..";
    }

    // RG12 : domaine pur (java.. et domaine du même contexte uniquement)
    @ArchTest
    static void rg12_le_domaine_ne_depend_que_de_java_et_du_domaine_de_son_contexte(JavaClasses classes) {
        for (String contexte : CONTEXTES) {
            classes().that().resideInAPackage(paquet(contexte, "domaine"))
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", paquet(contexte, "domaine"))
                    .as("RG12 : le domaine " + contexte + " ne dépend que de java.. et de son propre domaine")
                    .check(classes);
        }
    }

    // RG13 : application (domaine + application du contexte, stéréotype et transaction Spring)
    @ArchTest
    static void rg13_l_application_ne_depend_que_du_domaine_de_l_application_et_de_spring_stereotype_transaction(
            JavaClasses classes) {
        for (String contexte : CONTEXTES) {
            classes().that().resideInAPackage(paquet(contexte, "application"))
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..",
                            paquet(contexte, "domaine"),
                            paquet(contexte, "application"),
                            "org.springframework.stereotype..",
                            "org.springframework.transaction..")
                    .as("RG13 : l'application " + contexte + " reste dans domaine, application, stereotype et transaction")
                    .check(classes);
        }
    }

    // RG14 : dépendances uniquement vers l'intérieur
    @ArchTest
    static void rg14_les_dependances_vont_uniquement_vers_l_interieur(JavaClasses classes) {
        layeredArchitecture().consideringAllDependencies()
                .layer("Domaine").definedBy(RACINE + ".*.domaine..")
                .layer("Application").definedBy(RACINE + ".*.application..")
                .layer("Infrastructure").definedBy(RACINE + ".*.infrastructure..")
                .layer("Exposition").definedBy(RACINE + ".*.exposition..")
                .whereLayer("Domaine").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Exposition")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Exposition")
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("Exposition").mayNotBeAccessedByAnyLayer()
                .withOptionalLayers(true)
                .as("RG14 : dépendances uniquement vers l'intérieur")
                .check(classes);
    }

    // RG14 : cas explicite, l'exposition ne touche pas l'infrastructure (CA8)
    @ArchTest
    static void rg14_l_exposition_ne_depend_pas_de_l_infrastructure(JavaClasses classes) {
        classes().that().resideInAPackage(RACINE + ".*.exposition..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage(RACINE + ".*.infrastructure..")
                .as("RG14 : l'exposition ne dépend pas de l'infrastructure")
                .check(classes);
    }

    // RG15 : contextes délimités étanches (dans les deux sens)
    @ArchTest
    static void rg15_courses_ne_depend_pas_de_comptes(JavaClasses classes) {
        classes().that().resideInAPackage(RACINE + ".courses..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage(RACINE + ".comptes..")
                .as("RG15 : courses ne dépend pas de comptes")
                .check(classes);
    }

    @ArchTest
    static void rg15_comptes_ne_depend_pas_de_courses(JavaClasses classes) {
        classes().that().resideInAPackage(RACINE + ".comptes..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage(RACINE + ".courses..")
                .as("RG15 : comptes ne dépend pas de courses")
                .check(classes);
    }

    // RG16 : placement des contrôleurs, entités et classes
    @ArchTest
    static void rg16_les_controleurs_sont_dans_exposition(JavaClasses classes) {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .or().areAnnotatedWith("org.springframework.stereotype.Controller")
                .should().resideInAPackage("..exposition..")
                .as("RG16 : les contrôleurs sont dans une exposition");
        rule.check(classes);
    }

    @ArchTest
    static void rg16_les_entites_jpa_sont_dans_infrastructure(JavaClasses classes) {
        classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("..infrastructure..")
                .as("RG16 : les entités JPA sont dans une infrastructure")
                .check(classes);
    }

    // RG9 / RG16 : toute classe est dans <contexte>.<couche> (hors classe racine de l'application)
    @ArchTest
    static void rg09_rg16_toute_classe_est_dans_un_contexte_et_une_couche_connus(JavaClasses classes) {
        String[] paquets = CONTEXTES.stream()
                .flatMap(contexte -> COUCHES.stream().map(couche -> paquet(contexte, couche)))
                .toArray(String[]::new);
        classes().that().resideInAPackage(RACINE + "..")
                .and().doNotHaveFullyQualifiedName(BackyardUltraTrackerApplication.class.getName())
                .should().resideInAnyPackage(paquets)
                .as("RG9/RG16 : toute classe est dans fr.backyard.tracker.<comptes|courses>.<couche>")
                .check(classes);
    }
}
