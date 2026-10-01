package fr.backyard.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class SanteHttp {

    private SanteHttp() {
    }

    static HttpResponse<String> get(int port, String chemin) throws IOException, InterruptedException {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .header("Accept", "application/json")
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(requete, HttpResponse.BodyHandlers.ofString());
        }
    }
}
