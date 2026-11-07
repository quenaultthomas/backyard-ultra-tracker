package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 10.1a, CA22 : base vierge migrée jusqu'à 0012-photo-compte, ddl-auto=validate, puis
 * redémarrage du contexte sur la même base et le même répertoire : la photo est servie à l'identique (même ETag).
 */
@Testcontainers
class PhotoRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE = "un-mot-de-passe-12";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA22 : base vierge migrée jusqu'à 0012-photo-compte (validate), la photo survit au redémarrage du contexte avec le même répertoire et la même base : mêmes octets, même ETag, même photoUrl")
    void ca22_la_photo_survit_au_redemarrage() throws Exception {
        Path repertoire = Files.createTempDirectory("photos-it-redemarrage").resolve("photos");
        byte[] png = OutilsPhoto.png(1234);
        String url;
        String etag;
        try (ConfigurableApplicationContext premier = demarrer(repertoire)) {
            assertThat(premier.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            OutilsPhoto api = new OutilsPhoto(port(premier));
            String jeton = api.jetonCsrf();
            HttpResponse<byte[]> creation = api.requete("POST", "/api/comptes",
                    Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json",
                    OutilsPhoto.texte("{\"pseudo\":\"Alice\",\"motDePasse\":\"" + MOT_DE_PASSE + "\"}"));
            assertThat(creation.statusCode()).isEqualTo(201);
            OutilsPhoto.Session alice = api.ouvrir("Alice", MOT_DE_PASSE);
            HttpResponse<byte[]> envoi = api.envoyerPhoto(alice, png);
            assertThat(envoi.statusCode()).isEqualTo(200);
            url = JsonMapper.builder().build().readTree(new String(envoi.body())).get("photoUrl").asString();
            HttpResponse<byte[]> lue = api.get(url);
            assertThat(lue.statusCode()).isEqualTo(200);
            etag = lue.headers().firstValue("etag").orElseThrow();
        }

        try (ConfigurableApplicationContext second = demarrer(repertoire)) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            OutilsPhoto api = new OutilsPhoto(port(second));
            HttpResponse<byte[]> lue = api.get(url);
            assertThat(lue.statusCode()).isEqualTo(200);
            assertThat(lue.body()).isEqualTo(png);
            assertThat(lue.headers().firstValue("etag")).hasValue(etag);
            assertThat(lue.headers().firstValue("content-type")).hasValue("image/png");
            OutilsPhoto.Session alice = api.ouvrir("Alice", MOT_DE_PASSE);
            HttpResponse<byte[]> moi = api.get("/api/comptes/moi", alice);
            assertThat(JsonMapper.builder().build().readTree(new String(moi.body())).get("photoUrl").asString())
                    .isEqualTo(url);
        }
        assertThat(OutilsPhoto.noms(repertoire)).hasSize(1);
    }

    private ConfigurableApplicationContext demarrer(Path repertoire) {
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class).run(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--backyard.photos.repertoire=" + repertoire,
                "--backyard.admin-master.pseudo=Patron",
                "--backyard.admin-master.mot-de-passe=mot-de-passe-patron-1").toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext contexte) {
        return Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"));
    }
}
