package fr.backyard.tracker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.time.Clock;
import java.util.Set;

/**
 * CA4 (4.3, RG9, RG13) - tout composant temporel passe par le bean Clock.
 * Les surcharges qui prennent un Clock (Instant.now(horloge)) restent autorisées.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class HorlogeArchitectureTest {

    private static final String HORLOGE_CONFIGURATION =
            "fr.backyard.tracker.comptes.infrastructure.HorlogeConfiguration";

    private static final Set<String> TYPES_TEMPORELS = Set.of(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.ZonedDateTime",
            "java.time.OffsetDateTime");

    /** now() sans Clock en paramètre : now(), mais aussi now(ZoneId) qui lit l'horloge système. */
    private static boolean lit_l_horloge_systeme_via_now(JavaCall<?> appel) {
        return TYPES_TEMPORELS.contains(appel.getTargetOwner().getFullName())
                && appel.getTarget().getName().equals("now")
                && appel.getTarget().getRawParameterTypes().stream()
                        .noneMatch(type -> type.isEquivalentTo(Clock.class));
    }

    private static boolean lit_l_heure_via_system(JavaCall<?> appel) {
        return appel.getTargetOwner().isEquivalentTo(System.class)
                && appel.getTarget().getName().equals("currentTimeMillis");
    }

    private static boolean cree_une_horloge_systeme(JavaCall<?> appel) {
        return appel.getTargetOwner().isEquivalentTo(Clock.class)
                && appel.getTarget().getName().startsWith("system");
    }

    // CA4 / RG9 : seule HorlogeConfiguration peut construire ou lire l'horloge système
    @ArchTest
    static void ca4_aucune_classe_ne_lit_l_horloge_systeme_en_dehors_de_horloge_configuration(JavaClasses classes) {
        noClasses().that().doNotHaveFullyQualifiedName(HORLOGE_CONFIGURATION)
                .should().callMethodWhere(DescribedPredicate.describe(
                        "now() sans Clock, System.currentTimeMillis() ou Clock.system*()",
                        (JavaCall<?> appel) -> lit_l_horloge_systeme_via_now(appel)
                                || lit_l_heure_via_system(appel)
                                || cree_une_horloge_systeme(appel)))
                .as("CA4 : seule HorlogeConfiguration accède à l'horloge système, le reste utilise le bean Clock")
                .check(classes);
    }

    // CA4 / RG13 : le contrôleur de l'horloge ne dépend que du cas d'usage (ni port de réglage, ni infrastructure)
    @ArchTest
    static void ca4_le_controleur_de_l_horloge_ne_depend_que_du_cas_d_usage(JavaClasses classes) {
        noClasses().that().haveSimpleName("HorlogeController")
                .should().dependOnClassesThat().haveSimpleName("ReglageHorloge")
                .orShould().dependOnClassesThat().haveSimpleName("HorlogeDecalee")
                .orShould().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .allowEmptyShould(true)
                .as("CA4 : HorlogeController ne dépend que du cas d'usage PiloterHorloge")
                .check(classes);
    }
}
