package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 10.1a CA5 (une seule implémentation de l'effacement) et RG15 (placement des classes de la photo). Ces règles
 * échouent tant que les classes n'existent pas : aucun test vide. L'autorisation {@code domaine -> partage.domaine} des
 * règles génériques relève de CA22 (intégration).
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class PhotoArchitectureTest {

    private static final String COMPTES = "fr.backyard.tracker.comptes.";
    private static final String PARTAGE = "fr.backyard.tracker.partage.domaine";

    @ArchTest
    static void ca5_les_trois_suppressions_passent_par_le_composant_partage_effacement_photo(JavaClasses classes) {
        classes().that().haveSimpleName("SupprimerCompteCoureur")
                .or().haveSimpleName("SupprimerBenevole")
                .or().haveSimpleName("SupprimerAdmin")
                .should().dependOnClassesThat().haveSimpleName("EffacementPhoto")
                .as("CA5 : les suppressions de coureur, de bénévole et d'admin utilisent EffacementPhoto")
                .check(classes);
    }

    @ArchTest
    static void ca5_les_suppressions_n_utilisent_pas_le_port_de_stockage_directement(JavaClasses classes) {
        noClasses().that().haveSimpleName("SupprimerCompteCoureur")
                .or().haveSimpleName("SupprimerBenevole")
                .or().haveSimpleName("SupprimerAdmin")
                .should().dependOnClassesThat().haveSimpleName("StockagePhotos")
                .as("CA5 : les suppressions ne touchent pas au port StockagePhotos, seul EffacementPhoto l'utilise")
                .check(classes);
    }

    @ArchTest
    static void ca5_seuls_effacement_photo_et_enregistrer_photo_appellent_effacer(JavaClasses classes) {
        noClasses().that().doNotHaveSimpleName("EffacementPhoto")
                .and().doNotHaveSimpleName("EnregistrerPhoto")
                .should().callMethod("fr.backyard.tracker.comptes.domaine.StockagePhotos", "effacer",
                        "java.lang.String", "fr.backyard.tracker.partage.domaine.FormatImage")
                .as("CA5 : une seule implémentation de l'effacement (EffacementPhoto) ; EnregistrerPhoto n'annule que son fichier neuf")
                .check(classes);
    }

    @ArchTest
    static void rg15_le_modele_de_la_photo_est_dans_comptes_domaine(JavaClasses classes) {
        classes().that().haveSimpleName("Photo")
                .or().haveSimpleName("ReferencePhoto")
                .or().haveSimpleName("JetonPhoto")
                .or().haveSimpleName("StockagePhotos")
                .or().haveSimpleName("PhotoInvalideException")
                .or().haveSimpleName("PhotoIntrouvableException")
                .or().haveSimpleName("PhotoNonAutoriseeException")
                .should().resideInAPackage(COMPTES + "domaine")
                .as("RG15 : le modèle de la photo est dans comptes.domaine")
                .check(classes);
    }

    @ArchTest
    static void rg15_les_cas_d_usage_de_la_photo_sont_dans_comptes_application(JavaClasses classes) {
        classes().that().haveSimpleName("EnregistrerPhoto")
                .or().haveSimpleName("RetirerPhoto")
                .or().haveSimpleName("LirePhoto")
                .or().haveSimpleName("EffacementPhoto")
                .should().resideInAPackage(COMPTES + "application")
                .as("RG15 : les cas d'usage de la photo sont dans comptes.application")
                .check(classes);
    }

    @ArchTest
    static void rg15_stockage_photos_est_un_port(JavaClasses classes) {
        classes().that().haveSimpleName("StockagePhotos")
                .should().beInterfaces()
                .as("RG15 : StockagePhotos est un port (interface)")
                .check(classes);
    }

    @ArchTest
    static void rg15_format_image_est_partage_et_java_pur(JavaClasses classes) {
        classes().that().haveSimpleName("FormatImage")
                .should().resideInAPackage(PARTAGE)
                .andShould().onlyDependOnClassesThat().resideInAnyPackage("java..", PARTAGE)
                .as("RG15 : FormatImage est dans partage.domaine, Java pur, défini une seule fois")
                .check(classes);
    }
}
