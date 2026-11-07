package fr.backyard.tracker.comptes.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Outils des tests d'intégration de la photo de profil (10.1a) : client HTTP explicite (aucun cookie automatique, octets
 * bruts, HTTP/1.1), construction de corps multipart et d'images factices à signature valide, lecture du répertoire.
 */
final class OutilsPhoto {

    static final int TAILLE_MAXIMALE = 2 * 1024 * 1024;
    private static final byte[] SIGNATURE_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] SIGNATURE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private final int port;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    OutilsPhoto(int port) {
        this.port = port;
    }

    /** Cookies d'une session ouverte. */
    record Session(String id, String xsrf) {
        Map<String, String> entetes() {
            return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        }

        Map<String, String> enteteLecture() {
            return Map.of("Cookie", "JSESSIONID=" + id);
        }

        Map<String, String> sansJetonCsrf() {
            return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf);
        }
    }

    // ---------------------------------------------------------------- HTTP

    HttpResponse<byte[]> requete(String methode, String chemin, Map<String, String> entetes, String contentType,
                                 byte[] corps) throws IOException, InterruptedException {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .method(methode, corps == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corps));
        if (contentType != null) {
            requete.header("Content-Type", contentType);
        }
        entetes.forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> get(String chemin) throws IOException, InterruptedException {
        return requete("GET", chemin, Map.of(), null, null);
    }

    HttpResponse<byte[]> get(String chemin, Session session) throws IOException, InterruptedException {
        return requete("GET", chemin, session.enteteLecture(), null, null);
    }

    String jetonCsrf() throws IOException, InterruptedException {
        return valeurCookie(get("/api/csrf"), "XSRF-TOKEN");
    }

    static String valeurCookie(HttpResponse<?> reponse, String nom) {
        return reponse.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith(nom + "="))
                .map(c -> c.substring(nom.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucun cookie " + nom));
    }

    Session ouvrir(String pseudo, String motDePasse) throws IOException, InterruptedException {
        String jeton = jetonCsrf();
        HttpResponse<byte[]> reponse = requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json",
                ("{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        if (reponse.statusCode() != 200) {
            throw new AssertionError("Connexion de " + pseudo + " : " + reponse.statusCode());
        }
        return new Session(valeurCookie(reponse, "JSESSIONID"), jeton);
    }

    /** Requête anonyme avec un jeton CSRF valide mais aucune session. */
    Map<String, String> entetesAnonymesAvecCsrf() throws IOException, InterruptedException {
        String jeton = jetonCsrf();
        return Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
    }

    // ---------------------------------------------------------------- photo

    HttpResponse<byte[]> envoyerPhoto(Session session, byte[] octets) throws IOException, InterruptedException {
        return envoyerPhoto(session, octets, "photo.png", "image/png");
    }

    HttpResponse<byte[]> envoyerPhoto(Session session, byte[] octets, String nom, String type)
            throws IOException, InterruptedException {
        Multipart corps = new Multipart().partie("fichier", nom, type, octets);
        return envoyer(session, corps);
    }

    HttpResponse<byte[]> envoyer(Session session, Multipart corps) throws IOException, InterruptedException {
        return requete("PUT", "/api/comptes/moi/photo", session.entetes(), corps.contentType(), corps.octets());
    }

    HttpResponse<byte[]> retirerPhoto(Session session) throws IOException, InterruptedException {
        return requete("DELETE", "/api/comptes/moi/photo", session.entetes(), null, null);
    }

    /** Corps multipart fabriqué à la main : frontière fixe, parties dans l'ordre d'ajout. */
    static final class Multipart {
        private final String frontiere = "----photo" + UUID.randomUUID();
        private final ByteArrayOutputStream corps = new ByteArrayOutputStream();

        Multipart partie(String nom, String nomFichier, String type, byte[] contenu) {
            StringBuilder entete = new StringBuilder("--").append(frontiere).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(nom).append('"');
            if (nomFichier != null) {
                entete.append("; filename=\"").append(nomFichier).append('"');
            }
            entete.append("\r\n");
            if (type != null) {
                entete.append("Content-Type: ").append(type).append("\r\n");
            }
            entete.append("\r\n");
            corps.writeBytes(entete.toString().getBytes(StandardCharsets.UTF_8));
            corps.writeBytes(contenu);
            corps.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            return this;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + frontiere;
        }

        byte[] octets() {
            ByteArrayOutputStream complet = new ByteArrayOutputStream();
            complet.writeBytes(corps.toByteArray());
            complet.writeBytes(("--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return complet.toByteArray();
        }
    }

    // ---------------------------------------------------------------- images factices

    /** PNG à signature valide ({@code taille} octets au total, remplissage déterministe). */
    static byte[] png(int taille) {
        return avecSignature(SIGNATURE_PNG, taille, 1);
    }

    static byte[] jpeg(int taille) {
        return avecSignature(SIGNATURE_JPEG, taille, 2);
    }

    static byte[] webp(int taille) {
        byte[] octets = new byte[taille];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, octets, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, octets, 8, 4);
        remplir(octets, 12, 3);
        return octets;
    }

    /** JPEG contenant un bloc EXIF (marqueur FF E1 « Exif »). */
    static byte[] jpegAvecExif() {
        byte[] exif = {(byte) 0xFF, (byte) 0xE1, 0x00, 0x10, 'E', 'x', 'i', 'f', 0, 0, 'G', 'P', 'S', 'x', 'y', 'z'};
        byte[] debut = {(byte) 0xFF, (byte) 0xD8};
        byte[] corps = new byte[200];
        remplir(corps, 0, 5);
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        sortie.writeBytes(debut);
        sortie.writeBytes(exif);
        sortie.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE0});
        sortie.writeBytes(corps);
        return sortie.toByteArray();
    }

    private static byte[] avecSignature(byte[] signature, int taille, int graine) {
        byte[] octets = new byte[taille];
        System.arraycopy(signature, 0, octets, 0, Math.min(signature.length, taille));
        remplir(octets, signature.length, graine);
        return octets;
    }

    private static void remplir(byte[] octets, int depuis, int graine) {
        for (int i = depuis; i < octets.length; i++) {
            octets[i] = (byte) (31 * i + graine);
        }
    }

    static byte[] texte(String contenu) {
        return contenu.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concatener(byte[]... morceaux) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        Arrays.stream(morceaux).forEach(sortie::writeBytes);
        return sortie.toByteArray();
    }

    // ---------------------------------------------------------------- répertoire

    /** Noms des fichiers directs du répertoire (vide s'il n'existe pas), triés. */
    static List<String> noms(Path repertoire) throws IOException {
        if (!Files.isDirectory(repertoire)) {
            return List.of();
        }
        try (Stream<Path> fichiers = Files.list(repertoire)) {
            return fichiers.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    /** Vide récursivement le répertoire sans le supprimer. */
    static void vider(Path repertoire) throws IOException {
        if (!Files.isDirectory(repertoire)) {
            return;
        }
        try (Stream<Path> tout = Files.walk(repertoire)) {
            for (Path chemin : tout.sorted(Comparator.reverseOrder()).toList()) {
                if (!chemin.equals(repertoire)) {
                    Files.deleteIfExists(chemin);
                }
            }
        }
    }

    /** Jeton (32 hexadécimaux) extrait d'un {@code photoUrl}. */
    static String jetonDe(String photoUrl) {
        return photoUrl.substring("/api/photos/".length());
    }

    /** En-têtes d'une réponse, noms en minuscules, premières valeurs, pour comparaisons. */
    static Map<String, String> entetes(HttpResponse<?> reponse, String... noms) {
        Map<String, String> resultat = new LinkedHashMap<>();
        for (String nom : noms) {
            resultat.put(nom, reponse.headers().firstValue(nom).orElse(null));
        }
        return resultat;
    }
}
