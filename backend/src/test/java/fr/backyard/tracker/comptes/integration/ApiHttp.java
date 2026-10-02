package fr.backyard.tracker.comptes.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** Client HTTP minimal de test : aucun cookie géré automatiquement, tout est explicite. */
final class ApiHttp {

    static final String CORPS_VALIDE = "{\"pseudo\":\"%s\",\"motDePasse\":\"un-mot-de-passe-12\"}";

    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();

    ApiHttp(int port) {
        this.port = port;
    }

    HttpResponse<String> get(String chemin) throws IOException, InterruptedException {
        return envoyer(HttpRequest.newBuilder(uri(chemin)).header("Accept", "application/json").GET().build());
    }

    HttpResponse<String> post(String chemin, String contentType, String corps, Jeton jeton)
            throws IOException, InterruptedException {
        HttpRequest.Builder requete = HttpRequest.newBuilder(uri(chemin))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corps));
        if (contentType != null) {
            requete.header("Content-Type", contentType);
        }
        if (jeton != null) {
            if (jeton.cookie() != null) {
                requete.header("Cookie", "XSRF-TOKEN=" + jeton.cookie());
            }
            if (jeton.entete() != null) {
                requete.header("X-XSRF-TOKEN", jeton.entete());
            }
        }
        return envoyer(requete.build());
    }

    HttpResponse<String> postJson(String chemin, String corps, Jeton jeton) throws IOException, InterruptedException {
        return post(chemin, "application/json", corps, jeton);
    }

    /** Obtient un jeton CSRF valide via GET /api/csrf. */
    Jeton jetonValide() throws IOException, InterruptedException {
        String valeur = valeurCookieXsrf(get("/api/csrf"));
        return new Jeton(valeur, valeur);
    }

    static String valeurCookieXsrf(HttpResponse<?> reponse) {
        List<String> cookies = reponse.headers().allValues("set-cookie");
        return cookies.stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucun cookie XSRF-TOKEN dans " + cookies));
    }

    private HttpResponse<String> envoyer(HttpRequest requete) throws IOException, InterruptedException {
        return client.send(requete, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String chemin) {
        return URI.create("http://localhost:" + port + chemin);
    }

    record Jeton(String cookie, String entete) {
    }
}
