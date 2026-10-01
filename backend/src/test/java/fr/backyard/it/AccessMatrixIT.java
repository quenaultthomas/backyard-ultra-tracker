package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 6, CA1 (RG1) [IT] : matrice d'acces E1 a E25 x 4 profils (anonyme, compte coureur {@code Lievre},
 * SCANNER, ADMIN) sur la <strong>vraie</strong> chaine (HTTP Basic reel, securite reelle, services reels, H2 en mode
 * PostgreSQL), a partir d'UNE table unique (section 4 de la spec). Complement du test de slice
 * {@code AccessMatrixSliceTest} (services mockes) : ici, un acces autorise donne le statut reel du service.
 *
 * <p>Chaque cas repart d'un jeu de donnees neuf (les endpoints autorises modifient l'etat : demarrage, suppression,
 * DNF...), reconstruit par l'API reelle puis supprime en fin de cas. Les 401 n'ont jamais d'en-tete
 * {@code WWW-Authenticate} ; leur {@code detail} est « Authentification requise » sans en-tete Authorization et
 * « Identifiants invalides » avec des identifiants non reconnus par le referentiel du chemin (revision 4b).</p>
 */
@Tag("INC-6")
@Tag("INC6-CA1")
class AccessMatrixIT extends AbstractApiIT {

    private static final String PASSWORD = "motdepasse-1";
    private static final String LIEVRE = basic("Lievre", PASSWORD);
    private static final String ADMIN_BAD_PASSWORD = basic("admin-test", "mauvais");
    private static final String RACE_BODY = "{\"name\":\"IT6 matrice creee\",\"raceDate\":\"2026-10-03\","
        + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";
    private static final String RACE_UPDATE_BODY = "{\"name\":\"IT6 matrice modifiee\",\"raceDate\":\"2026-10-03\","
        + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";

    /** Etat de depart necessaire a un endpoint pour qu'une requete valide aboutisse. */
    private enum Scenario {
        /** R1 en SETUP avec Lievre (dossard 1) et R2 en SETUP. */
        SETUP,
        /** R1 demarree (horloge en yard 1), Lievre actif. */
        RUNNING,
        /** Comme RUNNING, Lievre en DNF (abandon volontaire au yard 1). */
        DNF
    }

    private enum Profile {
        ANON(null), RUN(LIEVRE), SCAN(SCANNER), ADM(ADMIN);

        private final String authorization;

        Profile(String authorization) {
            this.authorization = authorization;
        }
    }

    /** Une ligne de la section 4 : 0 = autorise (statut reel attendu {@code okStatus}), sinon le code d'erreur. */
    private record Endpoint(String id, HttpMethod method, String url, String body, Scenario scenario, int okStatus,
                            int anon, int run, int scan, int adm) {

        int expected(Profile profile) {
            int code = switch (profile) {
                case ANON -> anon;
                case RUN -> run;
                case SCAN -> scan;
                case ADM -> adm;
            };
            return code == 0 ? okStatus : code;
        }
    }

    private static final Scenario S = Scenario.SETUP;

    /** Table unique de la section 4 (E1 a E25). Jetons : {race}, {race2}, {runner}, {account}, {token}. */
    private static final List<Endpoint> MATRIX = List.of(
        new Endpoint("E1", HttpMethod.GET, "/api/public/races", null, S, 200, 0, 401, 0, 0),
        new Endpoint("E2", HttpMethod.GET, "/api/public/races/{race}", null, S, 200, 0, 401, 0, 0),
        new Endpoint("E3", HttpMethod.POST, "/api/public/races/{race}/registrations",
            "{\"pseudo\":\"Visiteur\",\"password\":\"motdepasse-1\"}", S, 201, 0, 401, 0, 0),
        new Endpoint("E4", HttpMethod.GET, "/api/public/races/{race}/board", null, S, 200, 0, 401, 0, 0),
        new Endpoint("E5", HttpMethod.GET, "/api/public/runners/{runner}", null, S, 200, 0, 401, 0, 0),
        new Endpoint("E6", HttpMethod.POST, "/api/scan/passages",
            "{\"qrToken\":\"{token}\",\"scannedAt\":\"2026-10-03T08:30:00Z\"}", Scenario.RUNNING, 200, 401, 401, 0, 0),
        new Endpoint("E7", HttpMethod.POST, "/api/admin/races", RACE_BODY, S, 201, 401, 401, 403, 0),
        new Endpoint("E8", HttpMethod.GET, "/api/admin/races", null, S, 200, 401, 401, 403, 0),
        new Endpoint("E9", HttpMethod.GET, "/api/admin/races/{race}", null, S, 200, 401, 401, 403, 0),
        new Endpoint("E10", HttpMethod.PUT, "/api/admin/races/{race}", RACE_UPDATE_BODY, S, 200, 401, 401, 403, 0),
        new Endpoint("E11", HttpMethod.DELETE, "/api/admin/races/{race}", null, S, 204, 401, 401, 403, 0),
        new Endpoint("E12", HttpMethod.POST, "/api/admin/races/{race}/start", null, S, 200, 401, 401, 403, 0),
        new Endpoint("E13", HttpMethod.GET, "/api/admin/races/{race}/runners", null, S, 200, 401, 401, 403, 0),
        new Endpoint("E14", HttpMethod.GET, "/api/admin/runners/{runner}", null, S, 200, 401, 401, 403, 0),
        new Endpoint("E15", HttpMethod.PUT, "/api/admin/runners/{runner}", "{\"bib\":7}", S, 200, 401, 401, 403, 0),
        new Endpoint("E16", HttpMethod.DELETE, "/api/admin/runners/{runner}", null, S, 204, 401, 401, 403, 0),
        new Endpoint("E17", HttpMethod.POST, "/api/admin/runners/{runner}/dnf", "{\"reason\":\"VOLUNTARY\"}",
            Scenario.RUNNING, 200, 401, 401, 403, 0),
        new Endpoint("E18", HttpMethod.POST, "/api/admin/runners/{runner}/reintegration", null, Scenario.DNF, 200,
            401, 401, 403, 0),
        new Endpoint("E19", HttpMethod.GET, "/api/scan/me", null, S, 200, 401, 401, 0, 0),
        new Endpoint("E20", HttpMethod.POST, "/api/account/races/{race2}/registrations", null, S, 201, 401, 0, 401,
            401),
        new Endpoint("E21", HttpMethod.GET, "/api/account/me", null, S, 200, 401, 0, 401, 401),
        new Endpoint("E22", HttpMethod.PUT, "/api/admin/accounts/{account}/password",
            "{\"newPassword\":\"motdepasse-2\"}", S, 204, 401, 401, 403, 0),
        new Endpoint("E23", HttpMethod.PUT, "/api/account/password", "{\"newPassword\":\"motdepasse-2\"}", S, 204, 401,
            0, 401, 401),
        new Endpoint("E24", HttpMethod.DELETE, "/api/admin/accounts/{account}", null, S, 204, 401, 401, 403, 0),
        new Endpoint("E25", HttpMethod.GET, "/api/admin/accounts", null, S, 200, 401, 401, 403, 0));

    @Autowired
    JdbcTemplate jdbc;

    private Long race1;
    private Long race2;
    private Long runner;
    private Long account;
    private String token;

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void wipeEverythingTheCaseCreated() {
        // E7, E3, E24, E16... creent ou detachent des lignes que le nettoyage par course d'AbstractApiIT ne suit pas
        jdbc.update("DELETE FROM passage");
        jdbc.update("DELETE FROM runner");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM race");
    }

    private void givenScenario(Scenario scenario) throws Exception {
        race1 = createSetupRace("IT6 matrice R1");
        race2 = createSetupRace("IT6 matrice R2");
        mvc.perform(post("/api/public/races/" + race1 + "/registrations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pseudo\":\"Lievre\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isCreated());
        account = jdbc.queryForObject("SELECT id FROM account WHERE pseudo = 'lievre'", Long.class);
        runner = jdbc.queryForObject("SELECT id FROM runner WHERE race_id = ?", Long.class, race1);
        token = jdbc.queryForObject("SELECT qr_token FROM runner WHERE id = ?", String.class, runner);
        if (scenario != Scenario.SETUP) {
            startRace(race1, "2026-10-03T08:00:00Z");
            clock.set(Instant.parse("2026-10-03T08:35:00Z"));
        }
        if (scenario == Scenario.DNF) {
            mvc.perform(post("/api/admin/runners/" + runner + "/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"VOLUNTARY\"}"))
                .andExpect(status().isOk());
        }
    }

    private String fill(String template) {
        return template == null ? null : template.replace("{race2}", String.valueOf(race2))
            .replace("{race}", String.valueOf(race1)).replace("{runner}", String.valueOf(runner))
            .replace("{account}", String.valueOf(account)).replace("{token}", token);
    }

    private MvcResult call(Endpoint endpoint, String authorization) throws Exception {
        MockHttpServletRequestBuilder request = request(endpoint.method(), fill(endpoint.url()));
        if (endpoint.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(fill(endpoint.body()));
        }
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn();
    }

    private static void assertUnauthorized(MvcResult result, boolean hadAuthorization) throws Exception {
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.detail"))
            .isEqualTo(hadAuthorization ? "Identifiants invalides" : "Authentification requise");
    }

    static Stream<Arguments> everyEndpointAndProfile() {
        return MATRIX.stream().flatMap(endpoint ->
            Stream.of(Profile.values()).map(profile -> Arguments.of(endpoint, profile)));
    }

    static Stream<Endpoint> endpoints() {
        return MATRIX.stream();
    }

    @Test
    @DisplayName("CA1 - la table couvre E1 a E25 une fois chacune, soit 100 cas")
    void ca1_theTableCoversE1ToE25Once() {
        assertThat(MATRIX).extracting(Endpoint::id)
            .containsExactlyElementsOf(IntStream.rangeClosed(1, 25).mapToObj(i -> "E" + i).toList());
        assertThat(everyEndpointAndProfile().count()).isEqualTo(100);
    }

    @ParameterizedTest(name = "CA1 reel - {0} / {1}")
    @MethodSource("everyEndpointAndProfile")
    @DisplayName("CA1 - matrice d'acces reelle : chaque endpoint E1 a E25 x anonyme, Lievre, SCANNER, ADMIN")
    void ca1_accessMatrixOnTheRealChain(Endpoint endpoint, Profile profile) throws Exception {
        // given
        givenScenario(endpoint.scenario());

        // when
        MvcResult result = call(endpoint, profile.authorization);

        // then
        int expected = endpoint.expected(profile);
        assertThat(result.getResponse().getStatus())
            .as("%s %s %s en tant que %s", endpoint.id(), endpoint.method(), fill(endpoint.url()), profile)
            .isEqualTo(expected);
        if (expected == 401) {
            assertUnauthorized(result, profile.authorization != null);
        }
    }

    @ParameterizedTest(name = "CA1 reel - {0} avec admin-test:mauvais : 401")
    @MethodSource("endpoints")
    @DisplayName("CA1 - des identifiants admin-test:mauvais donnent 401 sur E1 a E25")
    void ca1_wrongCredentialsAreAlways401(Endpoint endpoint) throws Exception {
        // given
        givenScenario(endpoint.scenario());

        // when
        MvcResult result = call(endpoint, ADMIN_BAD_PASSWORD);

        // then
        assertThat(result.getResponse().getStatus()).as("%s %s", endpoint.id(), fill(endpoint.url())).isEqualTo(401);
        assertUnauthorized(result, true);
    }
}
