package fr.backyard.api;

import fr.backyard.domain.Account;
import fr.backyard.domain.Pseudo;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.AccountCredentials;
import fr.backyard.service.AccountRegistrationsView;
import fr.backyard.service.RaceBoardView;
import fr.backyard.service.RunnerDetailView;
import fr.backyard.service.SessionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Spec increment 6, CA1 (RG1) [slice] : matrice d'acces E1 a E25 x 4 profils (anonyme, compte coureur Lievre,
 * SCANNER, ADMIN), a partir d'UNE table unique (section 4 de la spec). Securite reelle, vrais en-tetes
 * Authorization, services mockes. Aucun endpoint ne change : ce test fige l'existant.
 *
 * <p>Ecart consigne entre le texte de CA1 et le code livre : CA1 demande que « toutes les reponses 401 aient le
 * meme detail ». Le code distingue deux details voulus et deja figes par les tests de l'inc. 3 et 5
 * (AccountContractIT, SecuritySliceTest) : « Authentification requise » sans en-tete Authorization, et
 * « Identifiants invalides » avec des identifiants non reconnus par le referentiel du chemin. Ce test fige ces
 * deux details, selon la presence de l'en-tete ; il ne les unifie pas (a arbitrer par l'agent fonctionnel).
 */
@Tag("INC-6")
@Tag("INC6-CA1")
class AccessMatrixSliceTest extends ApiSliceTest {

    private static final String LIEVRE = basic("Lievre", "motdepasse-1");
    private static final String ADMIN_BAD_PASSWORD = basic("admin-test", "mauvais");
    private static final String SCAN_BODY = "{\"qrToken\":\"tok-b\",\"scannedAt\":\"2026-10-03T08:45:00Z\"}";
    private static final String RACE_BODY = "{\"name\":\"Backyard Test\",\"raceDate\":\"2026-10-03\","
        + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";
    private static final String REGISTRATION_BODY = "{\"pseudo\":\"Alice\",\"password\":\"motdepasse-1\"}";
    private static final String PASSWORD_BODY = "{\"newPassword\":\"motdepasse-2\"}";
    private static final String DNF_BODY = "{\"reason\":\"VOLUNTARY\"}";
    private static final String BIB_BODY = "{\"bib\":7}";

    /** Profils, dans l'ordre des colonnes de la table. */
    private enum Profile {
        ANON(null), RUN(LIEVRE), SCAN(SCANNER), ADM(ADMIN);

        private final String authorization;

        Profile(String authorization) {
            this.authorization = authorization;
        }
    }

    /** Une ligne de la section 4 : 0 = autorise (statut du service mocke), sinon le code d'erreur attendu. */
    private record Endpoint(String id, HttpMethod method, String url, String body, int okStatus,
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

    /** Table unique de la section 4 (E1 a E25). */
    private static final List<Endpoint> MATRIX = List.of(
        new Endpoint("E1", HttpMethod.GET, "/api/public/races", null, 200, 0, 401, 0, 0),
        new Endpoint("E2", HttpMethod.GET, "/api/public/races/1", null, 200, 0, 401, 0, 0),
        new Endpoint("E3", HttpMethod.POST, "/api/public/races/1/registrations", REGISTRATION_BODY, 201, 0, 401, 0, 0),
        new Endpoint("E4", HttpMethod.GET, "/api/public/races/1/board", null, 200, 0, 401, 0, 0),
        new Endpoint("E5", HttpMethod.GET, "/api/public/runners/12", null, 200, 0, 401, 0, 0),
        new Endpoint("E6", HttpMethod.POST, "/api/scan/passages", SCAN_BODY, 200, 401, 401, 0, 0),
        new Endpoint("E7", HttpMethod.POST, "/api/admin/races", RACE_BODY, 201, 401, 401, 403, 0),
        new Endpoint("E8", HttpMethod.GET, "/api/admin/races", null, 200, 401, 401, 403, 0),
        new Endpoint("E9", HttpMethod.GET, "/api/admin/races/1", null, 200, 401, 401, 403, 0),
        new Endpoint("E10", HttpMethod.PUT, "/api/admin/races/1", RACE_BODY, 200, 401, 401, 403, 0),
        new Endpoint("E11", HttpMethod.DELETE, "/api/admin/races/1", null, 204, 401, 401, 403, 0),
        new Endpoint("E12", HttpMethod.POST, "/api/admin/races/1/start", null, 200, 401, 401, 403, 0),
        new Endpoint("E13", HttpMethod.GET, "/api/admin/races/1/runners", null, 200, 401, 401, 403, 0),
        new Endpoint("E14", HttpMethod.GET, "/api/admin/runners/12", null, 200, 401, 401, 403, 0),
        new Endpoint("E15", HttpMethod.PUT, "/api/admin/runners/12", BIB_BODY, 200, 401, 401, 403, 0),
        new Endpoint("E16", HttpMethod.DELETE, "/api/admin/runners/12", null, 204, 401, 401, 403, 0),
        new Endpoint("E17", HttpMethod.POST, "/api/admin/runners/12/dnf", DNF_BODY, 200, 401, 401, 403, 0),
        new Endpoint("E18", HttpMethod.POST, "/api/admin/runners/12/reintegration", null, 200, 401, 401, 403, 0),
        new Endpoint("E19", HttpMethod.GET, "/api/scan/me", null, 200, 401, 401, 0, 0),
        new Endpoint("E20", HttpMethod.POST, "/api/account/races/1/registrations", null, 201, 401, 0, 401, 401),
        new Endpoint("E21", HttpMethod.GET, "/api/account/me", null, 200, 401, 0, 401, 401),
        new Endpoint("E22", HttpMethod.PUT, "/api/admin/accounts/5/password", PASSWORD_BODY, 204, 401, 401, 403, 0),
        new Endpoint("E23", HttpMethod.PUT, "/api/account/password", PASSWORD_BODY, 204, 401, 0, 401, 401),
        new Endpoint("E24", HttpMethod.DELETE, "/api/admin/accounts/5", null, 204, 401, 401, 403, 0),
        new Endpoint("E25", HttpMethod.GET, "/api/admin/accounts", null, 200, 401, 401, 403, 0));

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String LIEVRE_HASH = ENCODER.encode("motdepasse-1");

    private final Race r1 = backyardTest(RaceStatus.SETUP);
    private final Account lievre = account();
    private final Runner alice = runner(12L, r1, 6, TOKEN);

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Account account() {
        Account account = new Account("lievre", "$2a$12$" + "x".repeat(53));
        ReflectionTestUtils.setField(account, "id", 5L);
        return account;
    }

    @BeforeEach
    void servicesReturnValidResults() {
        when(accountService.findCredentials(anyString())).thenAnswer(inv ->
            "lievre".equals(Pseudo.normalize(inv.getArgument(0)))
                ? Optional.of(new AccountCredentials(5L, "lievre", LIEVRE_HASH))
                : Optional.empty());
        when(accountService.registrations(anyLong())).thenReturn(new AccountRegistrationsView("lievre", List.of()));
        when(sessionService.describe(anyString(), any())).thenAnswer(inv -> new SessionView(inv.getArgument(0),
            "SCANNER", Instant.parse("2026-10-03T10:00:00Z")));
        when(raceService.list()).thenReturn(List.of(r1));
        when(raceService.create(any())).thenReturn(r1);
        when(raceService.get(anyLong())).thenReturn(r1);
        when(raceService.update(anyLong(), any())).thenReturn(r1);
        when(raceService.start(anyLong())).thenReturn(r1);
        when(runnerService.register(1L, "Alice", "motdepasse-1")).thenReturn(alice);
        when(runnerService.registerAccount(1L, 5L)).thenReturn(alice);
        when(runnerService.listByRace(anyLong())).thenReturn(List.of(alice));
        when(runnerService.get(anyLong())).thenReturn(alice);
        when(runnerService.update(anyLong(), anyInt())).thenReturn(alice);
        when(manualDnfService.declareDnf(anyLong(), any())).thenReturn(alice);
        when(raceBoardService.board(1L)).thenReturn(new RaceBoardView(r1, at("07:00:00"), 0, null, List.of()));
        when(raceBoardService.runnerDetail(12L)).thenReturn(new RunnerDetailView(12L, 1L, 6, "Alice",
            RunnerStatus.ACTIVE, null, null, 0, 0L, 0L, OptionalInt.empty(), false, List.of()));
        when(passageRecordingService.recordScan(anyString(), any()))
            .thenReturn(withId(scan(alice, 1, at("08:45:00")), 40L));
    }

    static Stream<Arguments> everyEndpointAndProfile() {
        return MATRIX.stream().flatMap(endpoint ->
            Stream.of(Profile.values()).map(profile -> Arguments.of(endpoint, profile)));
    }

    private MvcResult call(Endpoint endpoint, String authorization) throws Exception {
        MockHttpServletRequestBuilder request = request(endpoint.method(), endpoint.url());
        if (endpoint.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn();
    }

    private static String detailOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return com.jayway.jsonpath.JsonPath.read(body, "$.detail");
    }

    private static void assertUnauthorized(MvcResult result, boolean hadAuthorization) throws Exception {
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) com.jayway.jsonpath.JsonPath.read(body, "$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(detailOf(result))
            .isEqualTo(hadAuthorization ? "Identifiants invalides" : "Authentification requise");
    }

    @Test
    @Tag("INC6-CA1")
    void ca1_theTableCoversE1ToE25Once() {
        assertThat(MATRIX).extracting(Endpoint::id)
            .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(i -> "E" + i).toList());
        assertThat(everyEndpointAndProfile().count()).isEqualTo(100);
    }

    @ParameterizedTest(name = "CA1 - {0} / {1}")
    @MethodSource("everyEndpointAndProfile")
    void ca1_accessMatrix(Endpoint endpoint, Profile profile) throws Exception {
        MvcResult result = call(endpoint, profile.authorization);

        int expected = endpoint.expected(profile);
        assertThat(result.getResponse().getStatus())
            .as("%s %s %s en tant que %s", endpoint.id(), endpoint.method(), endpoint.url(), profile)
            .isEqualTo(expected);
        if (expected == 401) {
            assertUnauthorized(result, profile.authorization != null);
        }
    }

    @ParameterizedTest(name = "CA1 - {0} avec admin-test:mauvais : 401")
    @MethodSource("endpoints")
    void ca1_wrongCredentialsAreAlways401(Endpoint endpoint) throws Exception {
        MvcResult result = call(endpoint, ADMIN_BAD_PASSWORD);

        assertThat(result.getResponse().getStatus()).as("%s %s", endpoint.id(), endpoint.url()).isEqualTo(401);
        assertUnauthorized(result, true);
    }

    static Stream<Endpoint> endpoints() {
        return MATRIX.stream();
    }
}
