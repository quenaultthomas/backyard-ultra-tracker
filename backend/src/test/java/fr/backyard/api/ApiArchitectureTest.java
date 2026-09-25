package fr.backyard.api;

import fr.backyard.domain.RunnerStatsCalculator;
import fr.backyard.domain.YardCalculator;
import fr.backyard.service.YardClosingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 3 - CA39 et CA45 (partie verifiable automatiquement de la revue de code, RG1, RG2, RG25) :
 * dependances des controllers et des services, par reflexion, sans contexte Spring.
 */
class ApiArchitectureTest {

    private static List<Class<?>> classesOf(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            if (isProductionClass(type)) {
                classes.add(type);
            }
        }
        return classes;
    }

    /** Exclut les classes de test compilees dans les memes packages (target/test-classes). */
    private static boolean isProductionClass(Class<?> type) {
        String location = type.getProtectionDomain().getCodeSource().getLocation().toString();
        return !location.contains("test-classes");
    }

    /** Types references par les champs, constructeurs et signatures de methodes d'une classe. */
    private static Set<Class<?>> referencedTypes(Class<?> type) {
        Set<Class<?>> types = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            types.add(field.getType());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            types.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        for (Method method : type.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
        }
        return types;
    }

    @Test
    @DisplayName("CA45 - aucun controller n'injecte de repository, de Clock, de YardCalculator ni de RunnerStatsCalculator")
    void ca45_controllersDoNotDependOnPersistenceClockOrCalculators() {
        List<Class<?>> apiClasses = classesOf("fr.backyard.api");
        assertThat(apiClasses).isNotEmpty();

        for (Class<?> apiClass : apiClasses) {
            assertThat(referencedTypes(apiClass))
                .as("dependances de %s", apiClass.getName())
                .noneMatch(t -> t.getPackageName().equals("fr.backyard.repository"))
                .doesNotContain(Clock.class, YardCalculator.class, RunnerStatsCalculator.class);
        }
    }

    @Test
    @DisplayName("CA39 - aucune classe de fr.backyard.api ne depend de YardClosingService")
    void ca39_noApiClassDependsOnYardClosingService() {
        for (Class<?> apiClass : classesOf("fr.backyard.api")) {
            assertThat(referencedTypes(apiClass))
                .as("dependances de %s", apiClass.getName())
                .doesNotContain(YardClosingService.class);
        }
    }

    @Test
    @DisplayName("CA45 / RG2 - aucun service ni element du domaine ne depend du package fr.backyard.api")
    void ca45_servicesDoNotDependOnApi() {
        List<Class<?>> coreClasses = new ArrayList<>(classesOf("fr.backyard.service"));
        coreClasses.addAll(classesOf("fr.backyard.domain"));
        assertThat(coreClasses).isNotEmpty();

        for (Class<?> coreClass : coreClasses) {
            assertThat(referencedTypes(coreClass))
                .as("dependances de %s", coreClass.getName())
                .noneMatch(t -> t.getPackageName().startsWith("fr.backyard.api"));
        }
    }
}
