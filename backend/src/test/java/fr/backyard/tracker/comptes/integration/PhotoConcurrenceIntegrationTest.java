package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 10.1a, CA19 (RG13) : deux requêtes simultanées sur le même coureur, 20 répétitions chacune.
 * Jamais de 500, jamais de fichier orphelin, état final cohérent entre la base et le répertoire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class PhotoConcurrenceIntegrationTest extends PhotoIntegrationBase {

    static final int REPETITIONS = 20;
    static final Path REPERTOIRE = repertoireTemporaire();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @Override
    Path repertoire() {
        return REPERTOIRE;
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-concurrence").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    @Test
    @DisplayName("CA19 (a) : deux PUT simultanés du même coureur : deux 200, un seul fichier dans le répertoire, celui du jeton en base (20 répétitions)")
    void ca19_deux_envois() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            OutilsPhoto.vider(REPERTOIRE);
            Coureur coureur = nouveauCoureur("EnvoiA" + i);
            byte[] premier = OutilsPhoto.png(300 + i);
            byte[] second = OutilsPhoto.jpeg(400 + i);

            List<HttpResponse<byte[]>> reponses = enParallele(
                    () -> api.envoyerPhoto(coureur.session(), premier),
                    () -> api.envoyerPhoto(coureur.session(), second, "p.jpg", "image/jpeg"));

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode).containsExactly(200, 200);
            Map<String, Object> colonnes = colonnesPhoto(coureur.id());
            String jeton = (String) colonnes.get("photo_jeton");
            String extension = "PNG".equals(colonnes.get("photo_format")) ? ".png" : ".jpg";
            assertThat(OutilsPhoto.noms(REPERTOIRE)).as("répétition " + i).containsExactly(jeton + extension);
            assertThat(api.get(CHEMIN_PHOTOS + jeton).statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("CA19 (b) : PUT et DELETE simultanés : jamais de 500, issue cohérente (photo et fichier, ou ni l'une ni l'autre) (20 répétitions)")
    void ca19_envoi_et_retrait() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            OutilsPhoto.vider(REPERTOIRE);
            Coureur coureur = nouveauCoureur("EnvoiB" + i);
            if (i % 2 == 0) {
                poserPhoto(coureur.session(), OutilsPhoto.png(250));
            }

            List<HttpResponse<byte[]>> reponses = enParallele(
                    () -> api.envoyerPhoto(coureur.session(), OutilsPhoto.png(300)),
                    () -> api.retirerPhoto(coureur.session()));

            assertThat(reponses.get(0).statusCode()).as("PUT " + i).isEqualTo(200);
            assertThat(reponses.get(1).statusCode()).as("DELETE " + i).isEqualTo(204);
            verifierCoherence(coureur.id(), i);
        }
    }

    @Test
    @DisplayName("CA19 (c) : PUT et suppression du Compte simultanés : jamais de 500, PUT 200 ou 401, Compte anonymisé et aucun fichier orphelin (20 répétitions)")
    void ca19_envoi_et_suppression_du_compte() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            OutilsPhoto.vider(REPERTOIRE);
            Coureur coureur = nouveauCoureur("EnvoiC" + i);

            List<HttpResponse<byte[]>> reponses = enParallele(
                    () -> api.envoyerPhoto(coureur.session(), OutilsPhoto.png(300)),
                    () -> api.requete("POST", "/api/comptes/moi/suppression", coureur.session().entetes(),
                            "application/json", OutilsPhoto.texte("{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}")));

            assertThat(reponses.get(0).statusCode()).as("PUT " + i).isIn(200, 401);
            assertThat(reponses.get(1).statusCode()).as("suppression " + i).isEqualTo(204);
            assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, coureur.id()))
                    .isEqualTo("Coureur anonyme");
            assertThat(colonnesPhoto(coureur.id())).as("répétition " + i).containsEntry("photo_jeton", null);
            assertThat(OutilsPhoto.noms(REPERTOIRE)).as("répétition " + i).isEmpty();
        }
    }

    @Test
    @DisplayName("CA19 (d) : PUT et passage en BENEVOLE simultanés : jamais de 500, PUT 200 ou refusé (403, ou 401 si la session est déjà invalidée), fichier cohérent avec la base (20 répétitions)")
    void ca19_envoi_et_changement_de_role() throws Exception {
        for (int i = 0; i < REPETITIONS; i++) {
            OutilsPhoto.vider(REPERTOIRE);
            Coureur coureur = nouveauCoureur("EnvoiD" + i);

            List<HttpResponse<byte[]>> reponses = enParallele(
                    () -> api.envoyerPhoto(coureur.session(), OutilsPhoto.png(300)),
                    () -> api.requete("POST", "/api/administration/coureurs/" + coureur.id() + "/passage-benevole",
                            nadia.entetes(), null, null));

            assertThat(reponses.get(0).statusCode()).as("PUT " + i).isIn(200, 401, 403);
            assertThat(reponses.get(1).statusCode()).as("passage " + i).isEqualTo(200);
            assertThat(jdbc.queryForObject("select role from compte where id = ?", String.class, coureur.id()))
                    .isEqualTo("BENEVOLE");
            verifierCoherence(coureur.id(), i);
            if (reponses.get(0).statusCode() == 200) {
                assertThat(colonnesPhoto(coureur.id()).get("photo_jeton")).as("photo conservée " + i).isNotNull();
            } else {
                assertThat(colonnesPhoto(coureur.id()).get("photo_jeton")).as("aucun jeton " + i).isNull();
                assertThat(OutilsPhoto.noms(REPERTOIRE)).as("aucun fichier " + i).isEmpty();
            }
        }
    }

    // ---------------------------------------------------------------- outils

    /** Base et répertoire concordent : soit une photo et son fichier, soit ni l'une ni l'autre. */
    private void verifierCoherence(UUID compte, int repetition) throws Exception {
        Map<String, Object> colonnes = colonnesPhoto(compte);
        String jeton = (String) colonnes.get("photo_jeton");
        if (jeton == null) {
            assertThat(colonnes.get("photo_format")).isNull();
            assertThat(OutilsPhoto.noms(REPERTOIRE)).as("répétition " + repetition).isEmpty();
        } else {
            assertThat(OutilsPhoto.noms(REPERTOIRE)).as("répétition " + repetition)
                    .containsExactly(jeton + ".png");
            assertThat(api.get(CHEMIN_PHOTOS + jeton).statusCode()).isEqualTo(200);
        }
    }

    private Coureur nouveauCoureur(String pseudo) throws Exception {
        UUID id = insererCompte(pseudo, "COUREUR");
        return new Coureur(id, api.ouvrir(pseudo, MOT_DE_PASSE));
    }

    private record Coureur(UUID id, OutilsPhoto.Session session) {
    }

    /** Lance les deux appels au même signal de départ, retourne leurs réponses dans l'ordre. */
    private List<HttpResponse<byte[]>> enParallele(Callable<HttpResponse<byte[]>> premier,
                                                    Callable<HttpResponse<byte[]>> second) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService executeur = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<byte[]>> a = executeur.submit(() -> {
                depart.await();
                return premier.call();
            });
            Future<HttpResponse<byte[]>> b = executeur.submit(() -> {
                depart.await();
                return second.call();
            });
            depart.countDown();
            return List.of(a.get(), b.get());
        } finally {
            executeur.shutdownNow();
        }
    }
}
