package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.integration.OutilsPhoto.Multipart;
import fr.backyard.tracker.comptes.integration.OutilsPhoto.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.catalina.Context;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.unit.DataSize;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Correctif 431 après un 413 de téléversement (CA2, CA3, CA5). Serveur réel (Tomcat seul, sans Caddy), base PostgreSQL
 * réelle. Le CA5 utilise une socket brute en keep-alive : la requête suivante d'un envoi refusé ne doit jamais être
 * perturbée par des octets restés sur la connexion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class TeleversementRefuseIntegrationTest extends PhotoIntegrationBase {

    static final Path REPERTOIRE = repertoireTemporaire();
    static final int ITERATIONS = 20;
    static final long HUIT_MIB = 8L * 1024 * 1024;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @Autowired
    Environment environnement;

    @Autowired
    ServletWebServerApplicationContext contexteWeb;

    @Override
    Path repertoire() {
        return REPERTOIRE;
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-televersement");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    // ---------------------------------------------------------------- CA2

    @Test
    @DisplayName("CA2 : max-swallow-size vaut 8 388 608 octets (connecteur), le contexte absorbe les envois refusés, multipart 2MB / 3MB inchangé")
    void ca2_reglages_tomcat_effectifs() {
        assertThat(environnement.getProperty("server.tomcat.max-swallow-size", DataSize.class))
                .isEqualTo(DataSize.ofBytes(8_388_608L));
        assertThat(environnement.getProperty("spring.servlet.multipart.max-file-size", DataSize.class))
                .isEqualTo(DataSize.ofMegabytes(2));
        assertThat(environnement.getProperty("spring.servlet.multipart.max-request-size", DataSize.class))
                .isEqualTo(DataSize.ofMegabytes(3));

        TomcatWebServer serveur = (TomcatWebServer) contexteWeb.getWebServer();
        AbstractHttp11Protocol<?> protocole =
                (AbstractHttp11Protocol<?>) serveur.getTomcat().getConnector().getProtocolHandler();
        assertThat(protocole.getMaxSwallowSize()).isEqualTo(8_388_608);
        // Le réglage appartient au Context Tomcat, pas au Connector (la spec dit « connecteur » par erreur).
        Context contexte = (Context) serveur.getTomcat().getHost().findChildren()[0];
        assertThat(contexte.getSwallowAbortedUploads()).isTrue();
    }

    // ---------------------------------------------------------------- CA3

    @Test
    @DisplayName("CA3 : max-swallow-size >= max_size du Caddyfile - max-request-size")
    void ca3_coherence_des_trois_limites() throws IOException {
        long swallow = environnement.getProperty("server.tomcat.max-swallow-size", DataSize.class).toBytes();
        long requete = environnement.getProperty("spring.servlet.multipart.max-request-size", DataSize.class).toBytes();
        String caddyfile = Files.readString(racine().resolve("frontend/Caddyfile"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("max_size\\s+(\\d+)\\s*(MiB|KiB|GiB|MB|KB|GB)").matcher(caddyfile);
        assertThat(m.find()).as("max_size introuvable dans frontend/Caddyfile").isTrue();
        long caddy = Long.parseLong(m.group(1)) * switch (m.group(2)) {
            case "KiB" -> 1024L;
            case "MiB" -> 1024L * 1024;
            case "GiB" -> 1024L * 1024 * 1024;
            case "KB" -> 1000L;
            case "MB" -> 1_000_000L;
            default -> 1_000_000_000L;
        };
        assertThat(swallow)
                .as("Incohérence : max-swallow-size (%d) doit être >= max_size Caddy (%d) - max-request-size (%d) = %d",
                        swallow, caddy, requete, caddy - requete)
                .isGreaterThanOrEqualTo(caddy - requete);
    }

    private static Path racine() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null && !Files.exists(courant.resolve("CHANGELOG.md"))) {
            courant = courant.getParent();
        }
        if (courant == null) {
            throw new IllegalStateException("Racine du dépôt introuvable");
        }
        return courant;
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : PUT photo de 3,5 / 5 / 8 Mo refusé en 413 PHOTO_TROP_VOLUMINEUSE, puis GET /api/comptes/moi en 200 sur la même connexion (20 itérations par taille)")
    void ca5_photo_trop_volumineuse_ne_perturbe_pas_la_requete_suivante() throws Exception {
        for (int taille : new int[] {3_500_000, 5_000_000, 8_000_000}) {
            Multipart corps = new Multipart().partie("fichier", "photo.png", "image/png", OutilsPhoto.png(taille));
            byte[] octets = corps.octets();
            try (Connexion connexion = new Connexion(port)) {
                for (int i = 0; i < ITERATIONS; i++) {
                    String contexte = "taille " + taille + ", itération " + i;
                    Reponse refus = connexion.envoyer("PUT", "/api/comptes/moi/photo", coureur1.entetes(),
                            corps.contentType(), octets);
                    assertThat(refus.statut()).as(contexte + " : " + refus.corps()).isEqualTo(413);
                    assertThat(refus.json().get("code").asString()).as(contexte).isEqualTo("PHOTO_TROP_VOLUMINEUSE");
                    Reponse suite = connexion.envoyer("GET", "/api/comptes/moi", coureur1.enteteLecture(), null, null);
                    assertThat(suite.statut()).as(contexte + " : " + suite.corps()).isEqualTo(200);
                    assertThat(suite.json().get("pseudo").asString()).as(contexte).isEqualTo("Coureur1");
                }
            }
        }
        assertThat(colonnesPhoto(idCoureur1).get("photo_jeton")).as("aucune photo posée").isNull();
    }

    @Test
    @DisplayName("CA5 : PUT logo de 5 000 000 octets refusé en 413 LOGO_TROP_VOLUMINEUX, puis GET de la course en 200 sur la même connexion (20 itérations)")
    void ca5_logo_trop_volumineux_ne_perturbe_pas_la_requete_suivante() throws Exception {
        UUID course = insererCourse("Backyard des Crêtes", "EN_PREPARATION");
        String chemin = "/api/administration/courses/" + course;
        Multipart corps = new Multipart().partie("fichier", "logo.png", "image/png", OutilsPhoto.png(5_000_000));
        byte[] octets = corps.octets();
        try (Connexion connexion = new Connexion(port)) {
            for (int i = 0; i < ITERATIONS; i++) {
                Reponse refus = connexion.envoyer("PUT", chemin + "/logo", patron.entetes(), corps.contentType(), octets);
                assertThat(refus.statut()).as("itération " + i + " : " + refus.corps()).isEqualTo(413);
                assertThat(refus.json().get("code").asString()).isEqualTo("LOGO_TROP_VOLUMINEUX");
                Reponse suite = connexion.envoyer("GET", chemin, patron.enteteLecture(), null, null);
                assertThat(suite.statut()).as("itération " + i + " : " + suite.corps()).isEqualTo(200);
                assertThat(suite.json().get("id").asString()).isEqualTo(course.toString());
                assertThat(suite.json().get("logoUrl").isNull()).as("logo inchangé").isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- socket brute en keep-alive

    record Reponse(int statut, Map<String, String> entetes, byte[] octets, tools.jackson.databind.json.JsonMapper mapper) {
        String corps() {
            return new String(octets, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            return mapper.readTree(corps());
        }
    }

    /** Connexion HTTP/1.1 persistante : écrit les requêtes à la main et lit les réponses, sans jamais rouvrir. */
    static final class Connexion implements AutoCloseable {
        private final int port;
        private Socket socket;
        private InputStream entree;
        private OutputStream sortie;
        /** Vrai quand le serveur a annoncé {@code Connection: close} : la requête suivante ouvre une connexion neuve. */
        private boolean fermee;
        private final tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        Connexion(int port) throws IOException {
            this.port = port;
            ouvrir();
        }

        private void ouvrir() throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(30_000);
            entree = new java.io.BufferedInputStream(socket.getInputStream());
            sortie = socket.getOutputStream();
            fermee = false;
        }

        Reponse envoyer(String methode, String chemin, Map<String, String> entetes, String contentType, byte[] corps)
                throws IOException {
            if (fermee) {
                // Tomcat seul clôt proprement la connexion après un 413 (Connection: close) : un client en ouvre une autre.
                socket.close();
                ouvrir();
            }
            StringBuilder tete = new StringBuilder(methode).append(' ').append(chemin).append(" HTTP/1.1\r\n")
                    .append("Host: localhost\r\nConnection: keep-alive\r\n");
            entetes.forEach((nom, valeur) -> tete.append(nom).append(": ").append(valeur).append("\r\n"));
            if (contentType != null) {
                tete.append("Content-Type: ").append(contentType).append("\r\n");
            }
            if (corps != null) {
                tete.append("Content-Length: ").append(corps.length).append("\r\n");
            }
            tete.append("\r\n");
            sortie.write(tete.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (corps != null) {
                sortie.write(corps);
            }
            sortie.flush();
            return lire();
        }

        private Reponse lire() throws IOException {
            String ligneStatut = ligne();
            assertThat(ligneStatut).as("ligne de statut (connexion coupée ?)").startsWith("HTTP/1.1 ");
            int statut = Integer.parseInt(ligneStatut.split(" ")[1]);
            Map<String, String> entetes = new LinkedHashMap<>();
            for (String l = ligne(); !l.isEmpty(); l = ligne()) {
                int deuxPoints = l.indexOf(':');
                entetes.put(l.substring(0, deuxPoints).trim().toLowerCase(Locale.ROOT), l.substring(deuxPoints + 1).trim());
            }
            byte[] octets;
            if ("chunked".equalsIgnoreCase(entetes.get("transfer-encoding"))) {
                ByteArrayOutputStream corps = new ByteArrayOutputStream();
                for (int taille = Integer.parseInt(ligne().trim(), 16); taille > 0;
                     taille = Integer.parseInt(ligne().trim(), 16)) {
                    corps.writeBytes(entree.readNBytes(taille));
                    ligne();
                }
                ligne();
                octets = corps.toByteArray();
            } else {
                octets = entree.readNBytes(Integer.parseInt(entetes.getOrDefault("content-length", "0")));
            }
            fermee = "close".equalsIgnoreCase(entetes.get("connection"));
            return new Reponse(statut, entetes, octets, mapper);
        }

        private String ligne() throws IOException {
            ByteArrayOutputStream ligne = new ByteArrayOutputStream();
            for (int c = entree.read(); c != '\n'; c = entree.read()) {
                if (c < 0) {
                    throw new IOException("Connexion coupée pendant la lecture de la réponse");
                }
                if (c != '\r') {
                    ligne.write(c);
                }
            }
            return ligne.toString(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
