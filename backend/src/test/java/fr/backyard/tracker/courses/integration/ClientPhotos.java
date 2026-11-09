package fr.backyard.tracker.courses.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Client de test des photos de profil (10.1) pour les tests du suivi 10.2 : envoi multipart, retrait, lecture publique. */
final class ClientPhotos {

    private final int port;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    ClientPhotos(int port) {
        this.port = port;
    }

    /** JPEG factice à signature valide. */
    static byte[] jpeg(int taille, int graine) {
        byte[] octets = new byte[taille];
        octets[0] = (byte) 0xFF;
        octets[1] = (byte) 0xD8;
        octets[2] = (byte) 0xFF;
        octets[3] = (byte) 0xE0;
        for (int i = 4; i < taille; i++) {
            octets[i] = (byte) (31 * i + graine);
        }
        return octets;
    }

    HttpResponse<byte[]> envoyer(ClientHttp.Session session, byte[] octets) throws IOException, InterruptedException {
        String frontiere = "----photo" + UUID.randomUUID();
        ByteArrayOutputStream corps = new ByteArrayOutputStream();
        corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"fichier\"; "
                + "filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        corps.writeBytes(octets);
        corps.writeBytes(("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return requete("PUT", "/api/comptes/moi/photo", session.entetes(),
                "multipart/form-data; boundary=" + frontiere, corps.toByteArray());
    }

    HttpResponse<byte[]> retirer(ClientHttp.Session session) throws IOException, InterruptedException {
        return requete("DELETE", "/api/comptes/moi/photo", session.entetes(), null, null);
    }

    HttpResponse<byte[]> supprimerCompte(ClientHttp.Session session, String motDePasse)
            throws IOException, InterruptedException {
        return requete("POST", "/api/comptes/moi/suppression", session.entetes(), "application/json",
                ("{\"motDePasseActuel\":\"" + motDePasse + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    HttpResponse<byte[]> lire(String methode, String chemin, Map<String, String> entetes)
            throws IOException, InterruptedException {
        return requete(methode, chemin, entetes, null, null);
    }

    private HttpResponse<byte[]> requete(String methode, String chemin, Map<String, String> entetes,
                                         String contentType, byte[] corps) throws IOException, InterruptedException {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .method(methode, corps == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corps));
        if (contentType != null) {
            requete.header("Content-Type", contentType);
        }
        entetes.forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
