package fr.backyard.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 4 - CA3 [back-IT] (RG53, RG5) : les fichiers produits par le build Angular (copiés dans
 * {@code target/classes/static} par le build Maven avant l'exécution des tests d'intégration) sont servis par
 * Spring Boot sur la liste fermée de {@link fr.backyard.config.PwaPaths}, avec les bons types et en-têtes de
 * cache, les routes du front renvoient {@code index.html}, et aucun autre chemin (y compris {@code /api/**})
 * n'est concerné.
 *
 * <p>Serveur embarqué réel ({@code webEnvironment = RANDOM_PORT}), pas {@code MockMvc} : le renvoi interne
 * ({@code forward:}) d'une route du front vers {@code index.html}, servi par un {@link
 * org.springframework.web.servlet.resource.ResourceHttpRequestHandler}, n'est pas rejoué par le dispatcher de
 * test de {@code MockMvc} en environnement simulé (le corps et le type de contenu de la ressource cible
 * restent alors vides) : seul un vrai conteneur Servlet exécute intégralement ce second aller. Un
 * {@link RestTemplate} nu (pas {@code TestRestTemplate}, absent du classpath de test dans cette configuration
 * Spring Boot 4.1 modulaire, non ajouté pour rester sans nouvelle dépendance) pointé sur {@link
 * LocalServerPort}, configuré pour ne jamais lever d'exception sur les statuts d'erreur.</p>
 */
@Tag("INC-4")
@Tag("INC4-CA3")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PwaStaticResourcesIT {

    private static final String SCANNER_AUTH = "Basic c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0";

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = nonThrowingRestTemplate();

    /** Statuts 4xx/5xx renvoyés normalement (statut observable), jamais convertis en exception. */
    private static RestTemplate nonThrowingRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                // Jamais d'erreur : le test lit le statut et les en-têtes de la réponse réelle,
                // y compris pour les 4xx/5xx (handleError n'est alors jamais appelé).
                return false;
            }
        });
        return template;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String readIndexHtml() throws IOException {
        return new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
    }

    /** Premier fichier {@code .js} référencé par une balise {@code <script src="...">} de {@code index.html}. */
    private static String fingerprintedJsFilePath() throws IOException {
        String html = readIndexHtml();
        Matcher matcher = Pattern.compile("<script[^>]*\\ssrc=\"([^\"]+\\.js)\"").matcher(html);
        assertThat(matcher.find())
            .as("index.html doit référencer au moins un fichier .js dans une balise <script src=\"...\">")
            .isTrue();
        String src = matcher.group(1);
        return src.startsWith("/") ? src : "/" + src;
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity(url(path), String.class);
    }

    private ResponseEntity<String> getWithAuth(String path, String authorizationHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String cacheControl(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
    }

    private static void assertContentTypeCompatible(ResponseEntity<?> response, MediaType expected) {
        MediaType actual = response.getHeaders().getContentType();
        assertThat(actual).as("content-type de la réponse").isNotNull();
        assertThat(actual.isCompatibleWith(expected))
            .as("content-type %s compatible avec %s", actual, expected).isTrue();
    }

    @Test
    @DisplayName("CA3 - GET / : 200 text/html (contenu de index.html), Cache-Control no-cache")
    void ca3_rootServesIndexHtmlWithNoCache() throws Exception {
        String indexHtml = readIndexHtml();

        ResponseEntity<String> response = get("/");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.TEXT_HTML);
        assertThat(cacheControl(response)).contains("no-cache");
        assertThat(response.getBody()).isEqualTo(indexHtml);
    }

    @Test
    @DisplayName("CA3 - HEAD / : 200")
    void ca3_headOnRootReturns200() {
        ResponseEntity<Void> response = restTemplate.exchange(url("/"), HttpMethod.HEAD, HttpEntity.EMPTY, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("CA3 - GET /manifest.webmanifest : 200 application/manifest+json")
    void ca3_manifestServedWithCorrectMediaType() {
        ResponseEntity<String> response = get("/manifest.webmanifest");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.parseMediaType("application/manifest+json"));
    }

    @Test
    @DisplayName("CA3 - GET /ngsw-worker.js : 200 text/javascript, Cache-Control no-cache")
    void ca3_ngswWorkerServedAsJavascriptNoCache() {
        ResponseEntity<String> response = get("/ngsw-worker.js");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.parseMediaType("text/javascript"));
        assertThat(cacheControl(response)).contains("no-cache");
    }

    @Test
    @DisplayName("CA3 - GET /ngsw.json : 200 application/json, Cache-Control no-cache")
    void ca3_ngswJsonServedAsJsonNoCache() {
        ResponseEntity<String> response = get("/ngsw.json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.APPLICATION_JSON);
        assertThat(cacheControl(response)).contains("no-cache");
    }

    @Test
    @DisplayName("CA3 - un fichier .js à empreinte référencé par index.html : 200 text/javascript, Cache-Control immutable")
    void ca3_fingerprintedJsFileServedImmutable() throws Exception {
        String path = fingerprintedJsFilePath();

        ResponseEntity<String> response = get(path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.parseMediaType("text/javascript"));
        assertThat(cacheControl(response)).contains("immutable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/courses/1", "/coureurs/1", "/admin/courses/1/qr", "/inscription/1", "/scan", "/connexion"})
    @DisplayName("CA3 - une route du front renvoie le contenu de index.html (200 text/html, Cache-Control no-cache)")
    void ca3_frontRoutesForwardToIndexHtml(String route) throws Exception {
        String indexHtml = readIndexHtml();

        ResponseEntity<String> response = get(route);

        assertThat(response.getStatusCode()).as("statut de GET %s", route).isEqualTo(HttpStatus.OK);
        assertContentTypeCompatible(response, MediaType.TEXT_HTML);
        assertThat(cacheControl(response)).as("Cache-Control de GET %s", route).contains("no-cache");
        assertThat(response.getBody()).as("corps de GET %s", route).isEqualTo(indexHtml);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/application.properties", "/nimporte-quoi", "/assets/../application.properties"})
    @DisplayName("CA3 - chemin hors de la liste fermée ou tentative de traversée : jamais 200")
    void ca3_unknownOrForbiddenPathsAreNeverServed(String path) {
        ResponseEntity<String> response = get(path);

        assertThat(response.getStatusCode().value()).as("statut de GET %s", path).isNotEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/courses/1"})
    @DisplayName("CA3 - POST sur une route du front (même dans la liste fermée) n'est jamais 2xx")
    void ca3_postOnFrontRoutesIsNeverAccepted(String route) {
        ResponseEntity<String> response = restTemplate.postForEntity(url(route), null, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).as("statut de POST %s", route).isFalse();
    }

    @Test
    @DisplayName("CA3 - un chemin /api/** n'est jamais renvoyé vers index.html : 401 sans contenu HTML")
    void ca3_apiPathsAreNeverForwardedToIndexHtml() {
        ResponseEntity<String> autre = get("/api/autre");
        assertThat(autre.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(autre.getHeaders().getContentType()).as("content-type de GET /api/autre")
            .isNotNull()
            .satisfies(contentType -> assertThat(contentType.toString()).doesNotContain("text/html"));

        ResponseEntity<String> adminRaces = get("/api/admin/races");
        assertThat(adminRaces.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("CA3 - matrice /api/** inchangée avec le compte SCANNER : 403 sur l'admin, 404 JSON sur le public")
    void ca3_apiMatrixUnchangedForScannerAccount() {
        ResponseEntity<String> adminRaces = getWithAuth("/api/admin/races", SCANNER_AUTH);
        assertThat(adminRaces.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> unknownRunner = getWithAuth("/api/public/races/999999", SCANNER_AUTH);
        assertThat(unknownRunner.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertContentTypeCompatible(unknownRunner, MediaType.APPLICATION_PROBLEM_JSON);
    }
}
