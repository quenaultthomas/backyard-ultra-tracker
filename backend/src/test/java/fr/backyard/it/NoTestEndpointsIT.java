package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 4 - CA7 [back-IT] (RG56) : au démarrage du contexte complet avec le profil "test", la liste
 * des correspondances de Spring MVC (contrôleurs {@code @RequestMapping}, ressources statiques et renvois vers
 * {@code index.html} de RG53) ne contient que E1 à E19 et les chemins fermés de {@code PwaPaths}. Aucun chemin
 * ne contient "test", "clock", "time", "reset" ou "close". Introspection réelle des beans
 * {@link RequestMappingHandlerMapping} et {@link AbstractUrlHandlerMapping} du contexte, pas une relecture des
 * seules constantes de {@code PwaPaths}.
 */
@Tag("INC-4")
@Tag("INC4-CA7")
class NoTestEndpointsIT extends AbstractApiIT {

    private static final List<String> FORBIDDEN_WORDS = List.of("test", "clock", "time", "reset", "close");

    /** E1 à E19 (docs/specs/increment3.md, docs/specs/increment4.md RG52) : un motif par groupe de méthodes
     * HTTP partageant un même chemin. 14 motifs uniques pour 19 méthodes {@code @RequestMapping}. */
    private static final Set<String> EXPECTED_API_PATTERNS = Set.of(
        "/api/admin/races",
        "/api/admin/races/{raceId}",
        "/api/admin/races/{raceId}/start",
        "/api/admin/races/{raceId}/runners",
        "/api/admin/runners/{runnerId}",
        "/api/admin/runners/{runnerId}/dnf",
        "/api/admin/runners/{runnerId}/reintegration",
        "/api/public/races",
        "/api/public/races/{raceId}",
        "/api/public/races/{raceId}/registrations",
        "/api/public/races/{raceId}/board",
        "/api/public/runners/{runnerId}",
        "/api/scan/passages",
        "/api/scan/me");

    @Autowired
    private ApplicationContext applicationContext;

    private Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> allRequestMappings() {
        Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> handlerMethods = new LinkedHashMap<>();
        applicationContext.getBeansOfType(RequestMappingHandlerMapping.class).values()
            .forEach(mapping -> handlerMethods.putAll(mapping.getHandlerMethods()));
        return handlerMethods;
    }

    /**
     * Motifs autorisés hors {@code /api/**} : le manifeste (RG53, servi par un {@code @Controller} explicite
     * pour son type de contenu) et le endpoint d'erreur standard de Spring Boot ({@code BasicErrorController}),
     * jamais un endpoint de test.
     */
    private static final Set<String> EXPECTED_NON_API_PATTERNS = Set.of("/error", "/manifest.webmanifest");

    @Test
    @DisplayName("CA7 - les correspondances @RequestMapping sous /api/** sont exactement E1 à E19 "
        + "(14 motifs uniques, 19 méthodes) ; les autres se limitent au manifeste et à /error")
    void ca7_requestMappingsAreExactlyTheDocumentedEndpoints() {
        Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> handlerMethods = allRequestMappings();

        Set<String> apiPatterns = new TreeSet<>();
        Set<String> nonApiPatterns = new TreeSet<>();
        int apiMethodCount = 0;
        for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entry
                : handlerMethods.entrySet()) {
            Set<String> patterns = entry.getKey().getPatternValues();
            boolean isApi = patterns.stream().allMatch(pattern -> pattern.startsWith("/api/"));
            if (isApi) {
                apiMethodCount++;
                apiPatterns.addAll(patterns);
            } else {
                nonApiPatterns.addAll(patterns);
            }
        }

        assertThat(apiMethodCount).as("nombre de méthodes @RequestMapping sous /api/** (E1 à E19)").isEqualTo(19);
        assertThat(apiPatterns).as("motifs uniques des correspondances @RequestMapping sous /api/**")
            .isEqualTo(EXPECTED_API_PATTERNS);
        assertThat(nonApiPatterns).as("motifs @RequestMapping hors /api/** (manifeste, erreur standard)")
            .isEqualTo(EXPECTED_NON_API_PATTERNS);
    }

    @Test
    @DisplayName("CA7 - aucun chemin de Spring MVC (API, ressources statiques, renvois vers index.html) "
        + "ne contient 'test', 'clock', 'time', 'reset' ou 'close'")
    void ca7_noMvcPathContainsForbiddenWords() {
        List<String> allPaths = new ArrayList<>();

        allRequestMappings().keySet().forEach(info -> allPaths.addAll(info.getPatternValues()));

        applicationContext.getBeansOfType(AbstractUrlHandlerMapping.class).values().forEach(mapping -> {
            mapping.getHandlerMap().keySet().forEach(allPaths::add);
            mapping.getPathPatternHandlerMap().keySet().forEach(key -> allPaths.add(key.toString()));
        });

        assertThat(allPaths).as("chemins Spring MVC analysés (API + ressources statiques + vues)").isNotEmpty();

        List<String> offending = new ArrayList<>();
        for (String path : allPaths) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_WORDS) {
                if (lower.contains(forbidden)) {
                    offending.add(path + " (contient '" + forbidden + "')");
                }
            }
        }

        assertThat(offending).as("chemins Spring MVC contenant un mot interdit (RG56)").isEmpty();
    }
}
