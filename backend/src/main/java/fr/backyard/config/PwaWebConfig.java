package fr.backyard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Service des fichiers de la PWA par Spring Boot (RG53 inc. 4, PO2 option A), entièrement dérivé de la liste
 * fermée de {@link PwaPaths} : aucun motif n'est déclaré ici.
 * <ul>
 *   <li>les routes du front de {@link PwaPaths#FRONT_ROUTES} renvoient {@code index.html} (statut 200) ;</li>
 *   <li>les fichiers à empreinte de {@link PwaPaths#FINGERPRINTED_FILES} ont un cache long et immuable ;</li>
 *   <li>les fichiers de {@link PwaPaths#ROOT_FILES} et les répertoires de {@link PwaPaths#ASSET_DIRECTORIES}
 *       sont servis en {@code no-cache} ;</li>
 *   <li>le manifeste est servi par {@code fr.backyard.web.PwaManifestController}, avec son type
 *       {@code application/manifest+json} (le mapping d'un controller est prioritaire).</li>
 * </ul>
 * Le service des ressources statiques par défaut de Spring Boot ({@code /**}) est désactivé
 * ({@code spring.web.resources.add-mappings=false}). Aucun chemin {@code /api/**} n'est concerné.
 */
@Configuration
public class PwaWebConfig implements WebMvcConfigurer {

    private static final CacheControl LONG_IMMUTABLE_CACHE =
        CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PwaPaths.FINGERPRINTED_FILES.toArray(String[]::new))
            .addResourceLocations(PwaPaths.STATIC_LOCATION)
            .setCacheControl(LONG_IMMUTABLE_CACHE);
        registry.addResourceHandler(PwaPaths.ROOT_FILES.stream().map(PwaWebConfig::singleFilePattern)
                .toArray(String[]::new))
            .addResourceLocations(PwaPaths.STATIC_LOCATION)
            .setCacheControl(CacheControl.noCache());
        PwaPaths.ASSET_DIRECTORIES.forEach(directory -> registry.addResourceHandler(directory)
            .addResourceLocations(PwaPaths.STATIC_LOCATION + directoryName(directory) + "/")
            .setCacheControl(CacheControl.noCache()));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        PwaPaths.FRONT_ROUTES.forEach(route -> registry.addViewController(route)
            .setViewName("forward:" + PwaPaths.INDEX_HTML));
    }

    /**
     * Motif d'un fichier précis de la racine, sous forme de variable à expression régulière
     * ({@code /ngsw-worker.js} donne <code>/{file:ngsw-worker\.js}</code>) : un motif littéral ne transmet aucun
     * chemin au service des ressources, et une variable est plus spécifique que les motifs {@code /*.js} des
     * fichiers à empreinte, ce qui garde les fichiers du service worker en {@code no-cache}.
     */
    static String singleFilePattern(String rootFile) {
        return "/{file:" + rootFile.substring(1).replace(".", "\\.") + "}";
    }

    /** {@code /icons/**} donne {@code icons}. */
    private static String directoryName(String directoryPattern) {
        return directoryPattern.substring(1, directoryPattern.indexOf("/**"));
    }
}
