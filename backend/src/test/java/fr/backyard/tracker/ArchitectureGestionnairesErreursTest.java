package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Incrément 7.3, CA13 (RG8) : garde-fou des gestionnaires d'erreurs. Le test échoue si un contrôleur de
 * comptes.exposition ou courses.exposition n'est couvert par aucun {@code @RestControllerAdvice}.
 * Prérequis : {@code fr.backyard.tracker.courses.exposition.GestionnaireErreursCourses} garde son nom.
 */
@AnalyzeClasses(packages = "fr.backyard.tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGestionnairesErreursTest {

    private static final String EXPOSITION_COURSES = "fr.backyard.tracker.courses.exposition";
    private static final String EXPOSITION_COMPTES = "fr.backyard.tracker.comptes.exposition";
    private static final String GESTIONNAIRE_COURSES = EXPOSITION_COURSES + ".GestionnaireErreursCourses";

    @ArchTest
    static void ca13_gestionnaire_courses_couvre_le_package_exposition_sans_liste_de_types(JavaClasses classes) {
        RestControllerAdvice advice = classes.get(GESTIONNAIRE_COURSES).reflect()
                .getAnnotation(RestControllerAdvice.class);

        assertThat(advice).as("GestionnaireErreursCourses est un @RestControllerAdvice").isNotNull();
        assertThat(advice.assignableTypes()).as("CA13 : aucun assignableTypes").isEmpty();
        assertThat(advice.annotations()).as("CA13 : aucun annotations").isEmpty();
        assertThat(avecAlias(advice.basePackages(), advice.value()))
                .as("CA13 : basePackages = courses.exposition")
                .containsExactly(EXPOSITION_COURSES);
    }

    @ArchTest
    static void ca13_chaque_rest_controller_est_couvert_par_un_rest_controller_advice(JavaClasses classes) {
        List<Class<?>> controleurs = classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .filter(c -> c.getPackageName().startsWith(EXPOSITION_COURSES)
                        || c.getPackageName().startsWith(EXPOSITION_COMPTES))
                .map(JavaClass::reflect).<Class<?>>map(c -> c).toList();
        List<RestControllerAdvice> gestionnaires = classes.stream()
                .filter(c -> c.isAnnotatedWith(RestControllerAdvice.class))
                .map(c -> c.reflect().getAnnotation(RestControllerAdvice.class)).toList();

        List<String> nonCouverts = controleurs.stream()
                .filter(controleur -> gestionnaires.stream().noneMatch(g -> couvre(g, controleur)))
                .map(Class::getName).toList();

        assertThat(controleurs).as("des contrôleurs sont analysés").isNotEmpty();
        assertThat(nonCouverts).as("CA13 : contrôleurs sans @RestControllerAdvice applicable").isEmpty();
    }

    private static boolean couvre(RestControllerAdvice advice, Class<?> controleur) {
        String[] paquets = avecAlias(advice.basePackages(), advice.value());
        boolean global = paquets.length == 0 && advice.basePackageClasses().length == 0
                && advice.assignableTypes().length == 0 && advice.annotations().length == 0;
        return global
                || Arrays.stream(paquets).anyMatch(p -> controleur.getPackageName().startsWith(p))
                || Arrays.stream(advice.basePackageClasses())
                        .anyMatch(c -> controleur.getPackageName().startsWith(c.getPackageName()))
                || Arrays.stream(advice.assignableTypes()).anyMatch(t -> t.isAssignableFrom(controleur))
                || Arrays.stream(advice.annotations()).anyMatch(a -> controleur.isAnnotationPresent(
                        a.asSubclass(Annotation.class)));
    }

    private static String[] avecAlias(String[] basePackages, String[] value) {
        return basePackages.length > 0 ? basePackages : value;
    }
}
