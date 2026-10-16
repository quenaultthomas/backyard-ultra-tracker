package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * SEC-06 (audit 2026-10-16) : les cas d'usage des endpoints publics qui calculent une empreinte Argon2id (connexion,
 * création de compte) ne l'exécutent pas dans une transaction, pour ne jamais tenir une connexion du pool pendant le
 * hachage. Les créations par un admin partagent le même déroulé. Test unitaire sans Spring.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class HachageHorsTransactionArchitectureTest {

    private static final String APPLICATION = "fr.backyard.tracker.comptes.application";
    private static final String[] CAS_D_USAGE = {"Connecter", "CreerCompteCoureur", "CreerAdmin", "CreerBenevole",
            "CreationCompte"};

    @ArchTest
    static void sec06_les_cas_d_usage_qui_hachent_ne_sont_pas_transactionnels(JavaClasses classes) {
        classes().that().resideInAPackage(APPLICATION).and().haveSimpleNameStartingWith("Cre")
                .or().haveSimpleName("Connecter")
                .should().notBeAnnotatedWith(Transactional.class)
                .allowEmptyShould(false)
                .as("SEC-06 : connexion et création de compte ne sont pas transactionnelles au niveau de la classe")
                .check(classes);
        for (String casDUsage : CAS_D_USAGE) {
            noMethods().that().areDeclaredIn(APPLICATION + "." + casDUsage)
                    .should().beAnnotatedWith(Transactional.class)
                    .allowEmptyShould(true)
                    .as("SEC-06 : aucune méthode de " + casDUsage + " n'ouvre de transaction autour du hachage")
                    .check(classes);
        }
    }
}
