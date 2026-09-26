package fr.backyard.config;

import java.util.List;
import java.util.stream.Stream;

/**
 * Liste fermée des chemins publics de la PWA servie par Spring Boot (RG53 inc. 4), définie ici seulement.
 * {@link SecurityConfig} les ouvre en GET et HEAD ; {@link PwaWebConfig} les sert. Aucun motif générique
 * {@code /**} : tout autre chemin hors {@code /api/**} reste refusé.
 */
public final class PwaPaths {

    /** Emplacement des fichiers produits par le build Angular dans le jar (RG59). */
    static final String STATIC_LOCATION = "classpath:/static/";

    /** Page unique de l'application, renvoyée pour chaque route du front. */
    static final String INDEX_HTML = "/index.html";

    /** Manifeste de la PWA, servi avec son type par {@code fr.backyard.web.PwaManifestController}. */
    public static final String MANIFEST = "/manifest.webmanifest";

    /**
     * Fichiers à la racine sans empreinte, servis sans cache long : page, manifeste, icône, fichiers du
     * service worker Angular.
     */
    static final List<String> ROOT_FILES = List.of(
        INDEX_HTML,
        MANIFEST,
        "/favicon.ico",
        "/ngsw-worker.js",
        "/ngsw.json",
        "/safety-worker.js",
        "/worker-basic.min.js");

    /** Fichiers à empreinte produits à la racine par le build : un seul segment. */
    static final List<String> FINGERPRINTED_FILES = List.of("/*.js", "/*.css");

    /** Répertoires de ressources statiques du build. */
    static final List<String> ASSET_DIRECTORIES = List.of("/icons/**", "/assets/**", "/media/**");

    /** Routes du front (RG5), qui renvoient le contenu de {@link #INDEX_HTML}. */
    static final List<String> FRONT_ROUTES = List.of(
        "/",
        "/courses/**",
        "/coureurs/**",
        "/inscription/**",
        "/connexion",
        "/scan",
        "/admin",
        "/admin/**");

    private PwaPaths() {
    }

    /** Tous les chemins ouverts en GET et HEAD sans authentification. */
    static String[] publicPaths() {
        return Stream.of(ROOT_FILES, FINGERPRINTED_FILES, ASSET_DIRECTORIES, FRONT_ROUTES)
            .flatMap(List::stream)
            .toArray(String[]::new);
    }
}
