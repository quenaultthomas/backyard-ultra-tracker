package fr.backyard.tracker.courses.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Client HTTP minimal de test : aucun cookie géré automatiquement, tout est explicite. */
final class ClientHttp {

    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();

    ClientHttp(int port) {
        this.port = port;
    }

    HttpResponse<String> requete(String methode, String chemin, Map<String, String> entetes, String contentType,
                                 String corps) throws IOException, InterruptedException {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .header("Accept", "application/json")
                .method(methode, corps == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corps));
        if (contentType != null) {
            requete.header("Content-Type", contentType);
        }
        entetes.forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Jeton CSRF valide (cookie et en-tête identiques) obtenu par GET /api/csrf. */
    String jetonValide() throws IOException, InterruptedException {
        return valeurCookie(requete("GET", "/api/csrf", Map.of(), null, null), "XSRF-TOKEN");
    }

    static String valeurCookie(HttpResponse<?> reponse, String nom) {
        return reponse.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith(nom + "="))
                .map(c -> c.substring(nom.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucun cookie " + nom));
    }

    /** Cookies d'une session ouverte. */
    record Session(String id, String xsrf) {
        Map<String, String> entetes() {
            return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        }

        Map<String, String> enteteLecture() {
            return Map.of("Cookie", "JSESSIONID=" + id);
        }
    }

    Session ouvrir(String pseudo, String motDePasse) throws IOException, InterruptedException {
        String jeton = jetonValide();
        HttpResponse<String> reponse = requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json",
                "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
        if (reponse.statusCode() != 200) {
            throw new AssertionError("Connexion de " + pseudo + " : " + reponse.statusCode() + " " + reponse.body());
        }
        return new Session(valeurCookie(reponse, "JSESSIONID"), jeton);
    }
}
