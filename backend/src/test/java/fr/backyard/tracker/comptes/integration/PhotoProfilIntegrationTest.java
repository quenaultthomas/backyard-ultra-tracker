package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.infrastructure.PhotosFichiersAdapter;
import fr.backyard.tracker.comptes.integration.OutilsPhoto.Multipart;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 10.1a : PUT et DELETE /api/comptes/moi/photo, GET et HEAD /api/photos/{jeton},
 * {@code photoUrl} de GET /api/comptes/moi (CA7 à CA11, CA13, CA17, CA21, CA22). Serveur réel, base PostgreSQL réelle,
 * migrations Liquibase réelles, répertoire de photos temporaire propre à la classe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class PhotoProfilIntegrationTest extends PhotoIntegrationBase {

    static final Pattern URL_PHOTO = Pattern.compile("/api/photos/[0-9a-f]{32}");
    static final Path BASE = repertoireTemporaire();
    static final Path REPERTOIRE = BASE.resolve("photos");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @Autowired
    Environment environnement;

    @MockitoSpyBean
    PhotosFichiersAdapter stockage;

    JournalApplicatif journal;

    @Override
    Path repertoire() {
        return REPERTOIRE;
    }

    @AfterEach
    void arreterJournal() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-profil");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : PUT PNG 2 000 octets : 200 PhotoReponse, photoUrl de GET /moi identique, colonnes en base, un seul fichier <jeton>.png, jeton distinct de l'identifiant ; PUT JPEG : autre URL, un seul fichier .jpg, ancienne URL 404")
    void ca7_envoi_puis_remplacement() throws Exception {
        byte[] png = OutilsPhoto.png(2000);

        HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, png);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = corps(reponse);
        assertThat(corps.propertyNames()).containsExactly("photoUrl");
        String url = corps.get("photoUrl").asString();
        assertThat(url).matches(URL_PHOTO);
        String jeton = OutilsPhoto.jetonDe(url);
        assertThat(photoUrlMoi(coureur1)).isEqualTo(url);
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton).containsEntry("photo_format", "PNG");
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertThat(Files.readAllBytes(REPERTOIRE.resolve(jeton + ".png"))).hasSize(2000).isEqualTo(png);
        assertThat(jeton).isNotEqualTo(idCoureur1.toString()).isNotEqualTo(idCoureur1.toString().replace("-", ""));
        assertServie(jeton, png);

        byte[] jpeg = OutilsPhoto.jpeg(3000);
        HttpResponse<byte[]> remplacement = api.envoyerPhoto(coureur1, jpeg, "photo.jpg", "image/jpeg");

        assertThat(remplacement.statusCode()).isEqualTo(200);
        String url2 = corps(remplacement).get("photoUrl").asString();
        assertThat(url2).matches(URL_PHOTO).isNotEqualTo(url);
        String jeton2 = OutilsPhoto.jetonDe(url2);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton2 + ".jpg");
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton2).containsEntry("photo_format",
                "JPEG");
        assertIntrouvable(jeton);
        assertServie(jeton2, jpeg);
        assertThat(photoUrlMoi(coureur1)).isEqualTo(url2);
    }

    @Test
    @DisplayName("CA7 : GET /api/comptes/moi d'un coureur sans photo : photoUrl null, avec les autres champs inchangés")
    void ca7_sans_photo_photoUrl_null() throws Exception {
        HttpResponse<byte[]> moi = api.get("/api/comptes/moi", coureur2);

        assertThat(moi.statusCode()).isEqualTo(200);
        JsonNode corps = corps(moi);
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe", "actif",
                "photoUrl");
        assertThat(corps.get("photoUrl").isNull()).isTrue();
        assertThat(corps.get("pseudo").asString()).isEqualTo("Coureur2");
        assertThat(photoUrlMoi(patron)).isNull();
    }

    @Test
    @DisplayName("CA7 : le même fichier envoyé deux fois donne deux 200, deux jetons différents et un seul fichier")
    void ca7_meme_fichier_deux_fois() throws Exception {
        byte[] png = OutilsPhoto.png(500);

        String premier = poserPhoto(coureur1, png);
        String second = poserPhoto(coureur1, png);

        assertThat(second).isNotEqualTo(premier);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(second + ".png");
        assertIntrouvable(premier);
        assertServie(second, png);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : GET public sans cookie : 200, octets identiques, en-têtes de service, ETag = jeton, aucun Set-Cookie ; If-None-Match : 304 sans corps avec ETag et Cache-Control")
    void ca8_lecture_publique() throws Exception {
        byte[] png = OutilsPhoto.png(2000);
        String jeton = poserPhoto(coureur1, png);

        HttpResponse<byte[]> reponse = api.get(CHEMIN_PHOTOS + jeton);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).isEqualTo(png);
        assertThat(reponse.headers().firstValue("content-type")).hasValue("image/png");
        assertThat(reponse.headers().firstValue("content-length")).hasValue("2000");
        assertThat(reponse.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(reponse.headers().firstValue("content-disposition")).hasValueSatisfying(
                d -> assertThat(d).startsWith("inline").doesNotContain("filename"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(reponse.headers().firstValue("etag")).hasValue("\"" + jeton + "\"");
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();

        HttpResponse<byte[]> inchange = api.requete("GET", CHEMIN_PHOTOS + jeton,
                Map.of("If-None-Match", "\"" + jeton + "\""), null, null);
        assertThat(inchange.statusCode()).isEqualTo(304);
        assertThat(inchange.body()).isEmpty();
        assertThat(inchange.headers().firstValue("etag")).hasValue("\"" + jeton + "\"");
        assertThat(inchange.headers().firstValue("cache-control")).hasValue("public, max-age=300");

        HttpResponse<byte[]> autreEtag = api.requete("GET", CHEMIN_PHOTOS + jeton,
                Map.of("If-None-Match", "\"" + "0".repeat(32) + "\""), null, null);
        assertThat(autreEtag.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA8 : le type servi vient des octets : JPEG déclaré image/png nommé x.txt servi image/jpeg ; WebP image/webp ; polyglotte JPEG + HTML servi image/jpeg avec nosniff")
    void ca8_type_servi_selon_les_octets() throws Exception {
        byte[] jpeg = OutilsPhoto.jpeg(800);
        assertThat(api.envoyerPhoto(coureur1, jpeg, "x.txt", "image/png").statusCode()).isEqualTo(200);
        String jeton = OutilsPhoto.jetonDe(photoUrlMoi(coureur1));
        HttpResponse<byte[]> lue = api.get(CHEMIN_PHOTOS + jeton);
        assertThat(lue.headers().firstValue("content-type")).hasValue("image/jpeg");
        assertThat(lue.body()).isEqualTo(jpeg);

        byte[] webp = OutilsPhoto.webp(600);
        assertThat(api.envoyerPhoto(coureur2, webp, "photo.png", "image/png").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> lueWebp = api.get(photoUrlMoi(coureur2));
        assertThat(lueWebp.headers().firstValue("content-type")).hasValue("image/webp");
        assertThat(lueWebp.body()).isEqualTo(webp);

        byte[] polyglotte = OutilsPhoto.concatener(OutilsPhoto.jpeg(300),
                OutilsPhoto.texte("<html><script>alert(1)</script></html>"));
        assertThat(api.envoyerPhoto(coureur3, polyglotte, "x.html", "text/html").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> luePoly = api.get(photoUrlMoi(coureur3));
        assertThat(luePoly.statusCode()).isEqualTo(200);
        assertThat(luePoly.headers().firstValue("content-type")).hasValue("image/jpeg");
        assertThat(luePoly.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(luePoly.headers().firstValue("content-disposition")).hasValueSatisfying(
                d -> assertThat(d).startsWith("inline"));
        assertThat(luePoly.body()).isEqualTo(polyglotte);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : jetons mal formés ou inconnus non encodés : 404 PHOTO_INTROUVABLE de corps identique sans écho du chemin, et aucun accès au stockage pour un format invalide")
    void ca9_jetons_hostiles_non_encodes() throws Exception {
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        List<String> invalides = List.of("abc", jeton.toUpperCase(), jeton.substring(1), jeton + "a", jeton + ".png",
                idCoureur1.toString(), "g".repeat(32), "photo.png");
        clearInvocations(stockage);

        List<String> corps = new ArrayList<>();
        for (String invalide : invalides) {
            HttpResponse<byte[]> reponse = api.get(CHEMIN_PHOTOS + invalide);
            assertProbleme(reponse, 404, "PHOTO_INTROUVABLE");
            String texte = new String(reponse.body(), StandardCharsets.UTF_8);
            assertThat(texte).doesNotContain(jeton);
            assertThat(corps(reponse).get("detail").asString()).isEqualTo("La photo est introuvable.");
            assertThat(corps(reponse).get("title").asString()).isEqualTo("Introuvable");
            corps.add(texte.replaceAll("\"instance\":\"[^\"]*\"", ""));
        }
        verifyNoInteractions(stockage);

        HttpResponse<byte[]> inconnu = api.get(CHEMIN_PHOTOS + "0123456789abcdef0123456789abcdef");
        assertProbleme(inconnu, 404, "PHOTO_INTROUVABLE");
        corps.add(new String(inconnu.body(), StandardCharsets.UTF_8).replaceAll("\"instance\":\"[^\"]*\"", ""));
        assertThat(corps).allSatisfy(c -> assertThat(c).isEqualTo(corps.get(0)));
        assertServie(jeton, OutilsPhoto.png(300));
    }

    @Test
    @DisplayName("CA9 : le corps du 404 PHOTO_INTROUVABLE ne reprend pas le chemin demandé (sans écho du chemin, contrat §4)")
    void ca9_corps_404_sans_echo_du_chemin() throws Exception {
        for (String invalide : List.of("abc", "ABCDEF0123456789ABCDEF0123456789", "0123456789abcdef0123456789abcdee",
                "photo.png")) {
            HttpResponse<byte[]> reponse = api.get(CHEMIN_PHOTOS + invalide);

            assertProbleme(reponse, 404, "PHOTO_INTROUVABLE");
            assertThat(new String(reponse.body(), StandardCharsets.UTF_8)).as(invalide).doesNotContain(invalide);
        }
    }

    @Test
    @DisplayName("CA9 : séquences de traversée (.., ..%2F, %2e%2e, %00) : jamais le contenu d'un fichier, jamais un 200 de type image (400 ou 404)")
    void ca9_sequences_encodees() throws Exception {
        poserPhoto(coureur1, OutilsPhoto.png(300));

        for (String chemin : List.of("..", "..%2F..%2Fetc%2Fpasswd", "%2e%2e%2f", "abc%00", "%2F", "%5C",
                "..%2F..%2F..%2Fetc%2Fhostname%00")) {
            HttpResponse<byte[]> reponse = api.get(CHEMIN_PHOTOS + chemin);
            assertThat(reponse.statusCode()).as(chemin).isIn(400, 404);
            assertThat(reponse.headers().firstValue("content-type").orElse("")).as(chemin).doesNotStartWith("image/");
            assertThat(new String(reponse.body(), StandardCharsets.UTF_8)).as(chemin).doesNotContain("root:");
        }
    }

    @Test
    @DisplayName("CA9 : noms de fichier hostiles ou longs jusqu'à 200 caractères (../../x.png, a\\b.png, a/b.png, x.png%00) ignorés : PUT 200 et aucun fichier hors du répertoire")
    void ca9_noms_hostiles_au_depot() throws Exception {
        poserPhoto(coureur1, OutilsPhoto.png(100));
        List<String> parentAvant = OutilsPhoto.noms(BASE);
        assertThat(parentAvant).containsExactly("photos");

        for (String nom : List.of("../../etc/x.png", "a\\b.png", "a/b.png", "x.png%00", "x".repeat(196) + ".png")) {
            HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, OutilsPhoto.png(200), nom, "image/png");

            assertThat(reponse.statusCode()).as(nom.length() > 40 ? "nom long" : nom).isEqualTo(200);
            String jeton = OutilsPhoto.jetonDe(corps(reponse).get("photoUrl").asString());
            assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
            assertThat(OutilsPhoto.noms(BASE)).isEqualTo(parentAvant);
            assertThat(OutilsPhoto.noms(BASE.getParent().resolve("etc"))).isEmpty();
        }
    }

    @Test
    @DisplayName("CA9 : nom de fichier de 500 caractères ou plus, ou contenant un véritable octet nul : refus 4xx de la plateforme, jamais 5xx, aucun fichier ni jeton créé, ancienne photo et base inchangées")
    void ca9_noms_refuses_par_la_plateforme() throws Exception {
        byte[] ancienne = OutilsPhoto.png(150);
        String jeton = poserPhoto(coureur1, ancienne);

        for (String nom : List.of("x".repeat(496) + ".png", "x".repeat(600) + ".png", "x".repeat(5000) + ".png",
                "a\u0000b.png")) {
            HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, OutilsPhoto.jpeg(200), nom, "image/jpeg");

            assertThat(reponse.statusCode()).as("nom de " + nom.length() + " caractères").isBetween(400, 499);
            assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton).containsEntry("photo_format",
                    "PNG");
            assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
            assertThat(OutilsPhoto.noms(BASE)).containsExactly("photos");
            assertServie(jeton, ancienne);
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : PUT par Léo, Nadia et Patron : 403 ACCES_REFUSE (même avec un fichier invalide), rien écrit")
    void ca10_roles_insuffisants() throws Exception {
        for (OutilsPhoto.Session session : List.of(leo, nadia, patron)) {
            assertProbleme(api.envoyerPhoto(session, OutilsPhoto.png(300)), 403, "ACCES_REFUSE");
            assertProbleme(api.envoyerPhoto(session, OutilsPhoto.texte("pas une image"), "photo.png", "image/png"),
                    403, "ACCES_REFUSE");
            assertProbleme(api.envoyer(session, new Multipart()), 403, "ACCES_REFUSE");
        }

        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from compte where photo_jeton is not null",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA10 : anonyme 401 NON_AUTHENTIFIE ; sans X-XSRF-TOKEN 403 CSRF_INVALIDE prioritaire, rien modifié ; DELETE : anonyme 401, sans CSRF 403")
    void ca10_authentification_et_csrf() throws Exception {
        Multipart png = new Multipart().partie("fichier", "photo.png", "image/png", OutilsPhoto.png(300));
        assertProbleme(api.requete("PUT", CHEMIN_PHOTO, api.entetesAnonymesAvecCsrf(), png.contentType(),
                png.octets()), 401, "NON_AUTHENTIFIE");

        assertProbleme(api.requete("PUT", CHEMIN_PHOTO, coureur1.sansJetonCsrf(), png.contentType(), png.octets()),
                403, "CSRF_INVALIDE");
        assertProbleme(api.requete("PUT", CHEMIN_PHOTO, leo.sansJetonCsrf(), png.contentType(), png.octets()), 403,
                "CSRF_INVALIDE");
        assertProbleme(api.requete("PUT", CHEMIN_PHOTO, Map.of(), png.contentType(), png.octets()), 403,
                "CSRF_INVALIDE");
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertThat(photoUrlMoi(coureur1)).isNull();

        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        assertProbleme(api.requete("DELETE", CHEMIN_PHOTO, api.entetesAnonymesAvecCsrf(), null, null), 401,
                "NON_AUTHENTIFIE");
        assertProbleme(api.requete("DELETE", CHEMIN_PHOTO, coureur1.sansJetonCsrf(), null, null), 403,
                "CSRF_INVALIDE");
        assertProbleme(api.requete("DELETE", CHEMIN_PHOTO, Map.of(), null, null), 403, "CSRF_INVALIDE");
        assertServie(jeton, OutilsPhoto.png(300));
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
    }

    @Test
    @DisplayName("CA10 : GET, POST, PATCH sur /api/comptes/moi/photo : 404 ou 405 ; POST, PUT, DELETE, PATCH sur /api/photos/<jeton> : anonyme 401, connecté 404 ou 405, jamais d'écriture")
    void ca10_autres_methodes() throws Exception {
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));

        assertThat(api.requete("GET", CHEMIN_PHOTO, coureur1.enteteLecture(), null, null).statusCode())
                .isIn(404, 405);
        assertThat(api.requete("POST", CHEMIN_PHOTO, coureur1.entetes(), null, null).statusCode()).isIn(404, 405);
        assertThat(api.requete("PATCH", CHEMIN_PHOTO, coureur1.entetes(), null, null).statusCode()).isIn(404, 405);

        Multipart png = new Multipart().partie("fichier", "photo.png", "image/png", OutilsPhoto.png(300));
        for (String methode : List.of("POST", "PUT", "DELETE", "PATCH")) {
            HttpResponse<byte[]> anonyme = api.requete(methode, CHEMIN_PHOTOS + jeton, api.entetesAnonymesAvecCsrf(),
                    png.contentType(), png.octets());
            assertProbleme(anonyme, 401, "NON_AUTHENTIFIE");
            HttpResponse<byte[]> connecte = api.requete(methode, CHEMIN_PHOTOS + jeton, coureur1.entetes(),
                    png.contentType(), png.octets());
            assertThat(connecte.statusCode()).as(methode).isIn(404, 405);
        }
        assertServie(jeton, OutilsPhoto.png(300));
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton);
    }

    @Test
    @DisplayName("CA10 : un identifiant (chemin, paramètre, partie) visant un autre Compte est sans effet : seule la photo de la session change")
    void ca10_aucun_identifiant_ne_vise_un_autre_compte() throws Exception {
        byte[] photoCoureur2 = OutilsPhoto.png(400);
        String jetonCoureur2 = poserPhoto(coureur2, photoCoureur2);

        Multipart corps = new Multipart()
                .partie("id", null, null, OutilsPhoto.texte(idCoureur2.toString()))
                .partie("compteId", null, null, OutilsPhoto.texte(idCoureur2.toString()))
                .partie("fichier", "photo.png", "image/png", OutilsPhoto.png(500));
        HttpResponse<byte[]> reponse = api.requete("PUT", CHEMIN_PHOTO + "?id=" + idCoureur2 + "&compteId="
                + idCoureur2, coureur1.entetes(), corps.contentType(), corps.octets());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(colonnesPhoto(idCoureur2)).containsEntry("photo_jeton", jetonCoureur2);
        assertServie(jetonCoureur2, photoCoureur2);
        assertThat(colonnesPhoto(idCoureur1).get("photo_jeton")).isNotNull().isNotEqualTo(jetonCoureur2);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).hasSize(2);

        HttpResponse<byte[]> chemin = api.requete("PUT", CHEMIN_PHOTO + "/" + idCoureur2, coureur1.entetes(),
                corps.contentType(), corps.octets());
        assertThat(chemin.statusCode()).isIn(404, 405);
        assertThat(api.requete("DELETE", CHEMIN_PHOTO + "/" + idCoureur2, coureur1.entetes(), null, null)
                .statusCode()).isIn(404, 405);
        assertServie(jetonCoureur2, photoCoureur2);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : fichier vide, partie absente, seulement une partie autre : 400 PHOTO_REQUISE ; faux PNG, SVG, GIF, PDF, HTML, PNG tronqué, RIFF+WAVE : 415 PHOTO_FORMAT_INVALIDE ; ancienne photo, répertoire et base inchangés")
    void ca11_refus_de_contenu_sans_effet() throws Exception {
        byte[] photoInitiale = OutilsPhoto.png(700);
        String jeton = poserPhoto(coureur1, photoInitiale);
        Map<String, Multipart> requis = new LinkedHashMap<>();
        requis.put("fichier vide", new Multipart().partie("fichier", "photo.png", "image/png", new byte[0]));
        requis.put("partie absente", new Multipart());
        requis.put("seulement une partie autre", new Multipart().partie("autre", null, null,
                OutilsPhoto.texte("valeur")));
        requis.put("autre partie avec fichier", new Multipart().partie("autre", "photo.png", "image/png",
                OutilsPhoto.png(300)));
        for (Map.Entry<String, Multipart> cas : requis.entrySet()) {
            HttpResponse<byte[]> reponse = api.envoyer(coureur1, cas.getValue());
            assertProbleme(reponse, 400, "PHOTO_REQUISE");
            assertThat(corps(reponse).get("title").asString()).as(cas.getKey()).isEqualTo("Requête invalide");
            assertThat(corps(reponse).get("detail").asString()).isEqualTo("Le fichier de la photo est obligatoire.");
            verifierInchange(jeton, photoInitiale);
        }

        Map<String, byte[]> invalides = new LinkedHashMap<>();
        invalides.put("faux PNG texte", OutilsPhoto.texte("ceci n'est pas une image"));
        invalides.put("SVG", OutilsPhoto.texte("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>"));
        invalides.put("GIF valide", OutilsPhoto.concatener(OutilsPhoto.texte("GIF89a"),
                new byte[] {1, 0, 1, 0, 0, 0, 0, 0x3B}));
        invalides.put("PDF", OutilsPhoto.texte("%PDF-1.7\n1 0 obj"));
        invalides.put("HTML", OutilsPhoto.texte("<!DOCTYPE html><html></html>"));
        invalides.put("PNG tronqué", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A});
        invalides.put("JPEG tronqué", new byte[] {(byte) 0xFF, (byte) 0xD8});
        invalides.put("RIFF seul", OutilsPhoto.texte("RIFF"));
        invalides.put("RIFF WAVE", OutilsPhoto.concatener(OutilsPhoto.texte("RIFF"), new byte[] {4, 0, 0, 0},
                OutilsPhoto.texte("WAVE"), new byte[40]));
        invalides.put("BMP", OutilsPhoto.concatener(OutilsPhoto.texte("BM"), new byte[60]));
        for (Map.Entry<String, byte[]> cas : invalides.entrySet()) {
            HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, cas.getValue(), "photo.png", "image/png");
            assertProbleme(reponse, 415, "PHOTO_FORMAT_INVALIDE");
            JsonNode probleme = corps(reponse);
            assertThat(probleme.get("title").asString()).as(cas.getKey()).isEqualTo("Format non supporté");
            assertThat(probleme.get("detail").asString()).isEqualTo("La photo doit être une image PNG, JPEG ou WebP.");
            assertThat(new String(reponse.body(), StandardCharsets.UTF_8)).doesNotContain("photo.png")
                    .doesNotContain("image/png");
            verifierInchange(jeton, photoInitiale);
        }
    }

    @Test
    @DisplayName("CA11 : vrai JPEG nommé photo.txt déclaré text/plain accepté et servi image/jpeg ; PNG et WebP valides acceptés ; JPEG avec bloc EXIF servi octet pour octet (aucun nettoyage)")
    void ca11_contenus_acceptes() throws Exception {
        byte[] jpeg = OutilsPhoto.jpeg(1200);
        assertThat(api.envoyerPhoto(coureur1, jpeg, "photo.txt", "text/plain").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> lue = api.get(photoUrlMoi(coureur1));
        assertThat(lue.headers().firstValue("content-type")).hasValue("image/jpeg");
        assertThat(lue.body()).isEqualTo(jpeg);

        assertThat(api.envoyerPhoto(coureur1, OutilsPhoto.png(1500), "photo.bin", "application/octet-stream")
                .statusCode()).isEqualTo(200);
        assertThat(api.get(photoUrlMoi(coureur1)).headers().firstValue("content-type")).hasValue("image/png");
        assertThat(api.envoyerPhoto(coureur1, OutilsPhoto.webp(1500), "photo.webp", "image/webp").statusCode())
                .isEqualTo(200);
        assertThat(api.get(photoUrlMoi(coureur1)).headers().firstValue("content-type")).hasValue("image/webp");

        byte[] avecExif = OutilsPhoto.jpegAvecExif();
        assertThat(api.envoyerPhoto(coureur2, avecExif, "gps.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        assertThat(api.get(photoUrlMoi(coureur2)).body()).isEqualTo(avecExif);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).hasSize(2);
    }

    @Test
    @DisplayName("CA11 : corps non multipart : 415 ; multipart mal formé : 400 CORPS_ILLISIBLE ; ancienne photo inchangée")
    void ca11_corps_non_multipart_et_mal_forme() throws Exception {
        byte[] photoInitiale = OutilsPhoto.png(700);
        String jeton = poserPhoto(coureur1, photoInitiale);

        HttpResponse<byte[]> json = api.requete("PUT", CHEMIN_PHOTO, coureur1.entetes(), "application/json",
                OutilsPhoto.texte("{}"));
        assertThat(json.statusCode()).isEqualTo(415);
        assertThat(api.requete("PUT", CHEMIN_PHOTO, coureur1.entetes(), "image/png", OutilsPhoto.png(300))
                .statusCode()).isEqualTo(415);
        verifierInchange(jeton, photoInitiale);

        HttpResponse<byte[]> sansFrontiere = api.requete("PUT", CHEMIN_PHOTO, coureur1.entetes(),
                "multipart/form-data", OutilsPhoto.texte("pas du multipart"));
        assertProbleme(sansFrontiere, 400, "CORPS_ILLISIBLE");
        assertThat(corps(sansFrontiere).get("title").asString()).isEqualTo("Requête invalide");
        verifierInchange(jeton, photoInitiale);
    }

    private void verifierInchange(String jeton, byte[] octets) throws Exception {
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton).containsEntry("photo_format", "PNG");
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertServie(jeton, octets);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : DELETE avec photo : 204 sans corps, colonnes nulles, fichier effacé, ancienne URL 404, photoUrl null ; répété : 204 ; la photo d'un autre coureur reste servie")
    void ca13_retrait_nominal_idempotent() throws Exception {
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        byte[] photoAutre = OutilsPhoto.jpeg(300);
        assertThat(api.envoyerPhoto(coureur2, photoAutre, "a.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        String jetonAutre = OutilsPhoto.jetonDe(photoUrlMoi(coureur2));

        HttpResponse<byte[]> reponse = api.retirerPhoto(coureur1);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", null).containsEntry("photo_format", null);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jetonAutre + ".jpg");
        assertIntrouvable(jeton);
        assertThat(photoUrlMoi(coureur1)).isNull();
        assertServie(jetonAutre, photoAutre);

        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);
        assertThat(api.retirerPhoto(coureur3).statusCode()).isEqualTo(204);
        assertServie(jetonAutre, photoAutre);
    }

    @Test
    @DisplayName("CA13 : DELETE par Léo (bénévole ex-coureur avec photo) : 204 et fichier effacé ; par Patron sans photo : 204")
    void ca13_retrait_par_un_non_coureur() throws Exception {
        String jeton = poserPhotoEnBase(idLeo, OutilsPhoto.jpeg(300));
        assertThat(photoUrlMoi(leo)).isEqualTo(CHEMIN_PHOTOS + jeton);
        assertServie(jeton, OutilsPhoto.jpeg(300));

        assertThat(api.retirerPhoto(leo).statusCode()).isEqualTo(204);

        assertThat(colonnesPhoto(idLeo)).containsEntry("photo_jeton", null);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertIntrouvable(jeton);
        assertThat(api.retirerPhoto(patron).statusCode()).isEqualTo(204);
        assertThat(api.retirerPhoto(nadia).statusCode()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : coureur avec photo passé en BENEVOLE par Nadia puis un autre en ADMIN par Patron : même photoUrl après reconnexion, photo servie, PUT 403 ACCES_REFUSE, DELETE 204")
    void ca17_conservation_au_changement_de_role() throws Exception {
        byte[] photo3 = OutilsPhoto.png(300);
        byte[] photo2 = OutilsPhoto.jpeg(300);
        String jeton3 = poserPhoto(coureur3, photo3);
        assertThat(api.envoyerPhoto(coureur2, photo2, "a.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        String url2 = photoUrlMoi(coureur2);

        assertThat(api.requete("POST", "/api/administration/coureurs/" + idCoureur3 + "/passage-benevole",
                nadia.entetes(), null, null).statusCode()).isEqualTo(200);
        assertThat(api.requete("POST", "/api/administration/coureurs/" + idCoureur2 + "/passage-admin",
                patron.entetes(), null, null).statusCode()).isEqualTo(200);

        OutilsPhoto.Session benevole = api.ouvrir("Coureur3", MOT_DE_PASSE);
        OutilsPhoto.Session admin = api.ouvrir("Coureur2", MOT_DE_PASSE);
        assertThat(photoUrlMoi(benevole)).isEqualTo(CHEMIN_PHOTOS + jeton3);
        assertThat(photoUrlMoi(admin)).isEqualTo(url2);
        assertServie(jeton3, photo3);
        assertThat(api.get(url2).body()).isEqualTo(photo2);
        assertProbleme(api.envoyerPhoto(benevole, OutilsPhoto.png(200)), 403, "ACCES_REFUSE");
        assertProbleme(api.envoyerPhoto(admin, OutilsPhoto.png(200)), 403, "ACCES_REFUSE");
        assertThat(photoUrlMoi(benevole)).isEqualTo(CHEMIN_PHOTOS + jeton3);

        assertThat(api.retirerPhoto(benevole).statusCode()).isEqualTo(204);
        assertThat(api.retirerPhoto(admin).statusCode()).isEqualTo(204);
        assertIntrouvable(jeton3);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
    }

    @Test
    @DisplayName("CA17 : désactivation puis réactivation d'un bénévole ex-coureur : photo toujours servie ; changement de mot de passe d'un coureur : photo intacte")
    void ca17_conservation_desactivation_et_mot_de_passe() throws Exception {
        byte[] photoLeo = OutilsPhoto.jpeg(300);
        String jetonLeo = poserPhotoEnBase(idLeo, photoLeo);

        assertThat(api.requete("POST", "/api/administration/benevoles/" + idLeo + "/desactivation", nadia.entetes(),
                null, null).statusCode()).isEqualTo(204);
        assertServie(jetonLeo, photoLeo);
        assertThat(api.requete("POST", "/api/administration/benevoles/" + idLeo + "/reactivation", nadia.entetes(),
                null, null).statusCode()).isEqualTo(204);
        assertServie(jetonLeo, photoLeo);
        assertThat(photoUrlMoi(api.ouvrir("Léo", MOT_DE_PASSE))).isEqualTo(CHEMIN_PHOTOS + jetonLeo);

        byte[] photo = OutilsPhoto.png(300);
        String jeton = poserPhoto(coureur1, photo);
        HttpResponse<byte[]> changement = api.requete("PUT", "/api/comptes/moi/mot-de-passe", coureur1.entetes(),
                "application/json", OutilsPhoto.texte("{\"motDePasseActuel\":\"" + MOT_DE_PASSE
                        + "\",\"nouveauMotDePasse\":\"nouveau-mot-de-passe-1\"}"));
        assertThat(changement.statusCode()).isIn(200, 204);
        assertServie(jeton, photo);
        assertThat(photoUrlMoi(api.ouvrir("Coureur1", "nouveau-mot-de-passe-1"))).isEqualTo(CHEMIN_PHOTOS + jeton);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactlyInAnyOrder(jeton + ".png", jetonLeo + ".jpg");
    }

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : journal réel : une ligne INFO par envoi, retrait et effacement (avec l'identifiant du Compte), aucune pour un refus 415, aucun jeton, chemin, nom d'origine ni pseudo")
    void ca21_journal() throws Exception {
        journal = JournalApplicatif.demarrer();
        journal.niveau("fr.backyard.tracker", Level.INFO);
        int marque = journal.marque();

        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        int apresEnvoi = journal.marque();
        HttpResponse<byte[]> refus = api.envoyerPhoto(coureur1, OutilsPhoto.texte("pas une image"),
                "nom-secret-origine.png", "image/png");
        assertThat(refus.statusCode()).isEqualTo(415);
        List<ILoggingEvent> pendantRefus = journal.applicatifsDepuis(apresEnvoi);
        assertThat(pendantRefus).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).isEmpty();

        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);
        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);
        String jeton2 = poserPhoto(coureur2, OutilsPhoto.jpeg(300));
        HttpResponse<byte[]> suppression = api.requete("POST", "/api/comptes/moi/suppression", coureur2.entetes(),
                "application/json", OutilsPhoto.texte("{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}"));
        assertThat(suppression.statusCode()).isEqualTo(204);

        List<String> lignes = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Photo de profil")).toList();
        assertThat(lignes).containsExactly(
                "Photo de profil enregistrée (compte " + idCoureur1 + ")",
                "Photo de profil retirée (compte " + idCoureur1 + ")",
                "Photo de profil enregistrée (compte " + idCoureur2 + ")",
                "Photo de profil effacée (compte " + idCoureur2 + ")");
        for (String interdit : List.of(jeton, jeton2, REPERTOIRE.toString(), "nom-secret-origine", "Coureur1",
                "Coureur2", "pas une image")) {
            assertThat(journal.messagesEtExceptions()).as(interdit)
                    .noneSatisfy(message -> assertThat(message).contains(interdit));
        }
    }

    // ---------------------------------------------------------------- CA22

    @Test
    @DisplayName("CA22 : databasechangelog se termine par 0012-photo-compte, schéma validé par Hibernate, colonnes photo_jeton et photo_format nullables")
    void ca22_changeset_et_schema() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif", "0012-photo-compte");
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForList("select column_name || ':' || data_type || ':' || character_maximum_length || ':'"
                + " || is_nullable from information_schema.columns where table_name = 'compte' and column_name like "
                + "'photo%' order by column_name", String.class))
                .containsExactly("photo_format:character varying:5:YES", "photo_jeton:character varying:32:YES");
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'compte'::regclass",
                String.class)).contains("ck_compte_photo_jeton_format", "ck_compte_photo_format",
                "ck_compte_photo_coherente", "uk_compte_photo_jeton");
    }

    @Test
    @DisplayName("CA22 : les contraintes refusent un jeton mal formé, un doublon, un jeton sans format, un format sans jeton et un format inconnu")
    void ca22_contraintes() {
        String valide = "0123456789abcdef0123456789abcdef";
        assertThatThrownBy(() -> poserEnBase(idCoureur1, "0123456789ABCDEF0123456789ABCDEF", "PNG"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_jeton_format");
        assertThatThrownBy(() -> poserEnBase(idCoureur1, "g".repeat(32), "PNG"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_jeton_format");
        assertThatThrownBy(() -> poserEnBase(idCoureur1, "abc", "PNG"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_jeton_format");
        assertThatThrownBy(() -> poserEnBase(idCoureur1, valide, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_coherente");
        assertThatThrownBy(() -> poserEnBase(idCoureur1, null, "PNG"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_coherente");
        assertThatThrownBy(() -> poserEnBase(idCoureur1, valide, "GIF"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_compte_photo_format");

        poserEnBase(idCoureur1, valide, "WEBP");
        assertThatThrownBy(() -> poserEnBase(idCoureur2, valide, "PNG"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_compte_photo_jeton");
        poserEnBase(idCoureur2, UUID.randomUUID().toString().replace("-", ""), "JPEG");
        poserEnBase(idCoureur3, null, null);
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", valide).containsEntry("photo_format",
                "WEBP");
    }

    @Test
    @DisplayName("CA22 : HEAD anonyme : 200 avec les mêmes en-têtes que GET et sans corps, 404 pour un jeton inconnu, sans authentification ni cookie")
    void ca22_head_public() throws Exception {
        byte[] png = OutilsPhoto.png(2000);
        String jeton = poserPhoto(coureur1, png);
        HttpResponse<byte[]> get = api.get(CHEMIN_PHOTOS + jeton);

        HttpResponse<byte[]> head = api.requete("HEAD", CHEMIN_PHOTOS + jeton, Map.of(), null, null);

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        String[] noms = {"content-type", "content-length", "x-content-type-options", "content-disposition",
            "cache-control", "etag"};
        assertThat(OutilsPhoto.entetes(head, noms)).isEqualTo(OutilsPhoto.entetes(get, noms));
        assertThat(head.headers().firstValue("content-length")).hasValue("2000");
        assertThat(head.headers().allValues("set-cookie")).isEmpty();

        HttpResponse<byte[]> inconnu = api.requete("HEAD", CHEMIN_PHOTOS + "0123456789abcdef0123456789abcdef",
                Map.of(), null, null);
        assertThat(inconnu.statusCode()).isEqualTo(404);
        assertThat(inconnu.body()).isEmpty();
        assertThat(api.requete("HEAD", CHEMIN_PHOTOS + "abc", Map.of(), null, null).statusCode()).isEqualTo(404);
        assertThat(api.requete("HEAD", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", "\"" + jeton + "\""), null,
                null).statusCode()).isEqualTo(304);
    }

    @Test
    @DisplayName("CA22 : la variable du répertoire des photos, fixée par docker-compose, n'est dans .env.example que pour mémoire (ligne commentée, correctif audit jalon 10 RG1)")
    void ca22_env_example_inchange() throws Exception {
        Path envExemple = Path.of("..", ".env.example");

        assertThat(envExemple).exists();
        // Correctif audit jalon 10, RG1 (remplace 10.1 RG16f « .env.example inchangé ») : la variable n'apparaît que
        // commentée, pour mémoire ; aucune valeur active ne peut être posée par .env.
        assertThat(Files.readString(envExemple)).doesNotContainPattern("(?m)^\\s*BACKYARD_PHOTOS_REPERTOIRE\\s*=")
                .contains("# BACKYARD_PHOTOS_REPERTOIRE=/donnees/photos");
        assertThat(Files.readString(Path.of("..", "docker-compose.yml")))
                .contains("BACKYARD_PHOTOS_REPERTOIRE: /donnees/photos").contains("photos:");
    }

    private void poserEnBase(UUID compte, String jeton, String format) {
        jdbc.update("update compte set photo_jeton = ?, photo_format = ? where id = ?", jeton, format, compte);
    }
}
