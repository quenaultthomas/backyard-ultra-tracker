package fr.backyard.api;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.service.RaceBoardView;
import fr.backyard.service.RunnerDetailView;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.namedRace;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 3 - RG28 a RG31, RG33, CA46 a CA56 [slice]. Toutes les requetes portent un vrai en-tete
 * Authorization (ou aucun), jamais @WithMockUser, pour passer par le filtre HTTP Basic reel.
 */
class SecuritySliceTest extends ApiSliceTest {

    private static final String SCAN_BODY = "{\"qrToken\":\"tok-b\",\"scannedAt\":\"2026-10-03T08:45:00Z\"}";
    private static final String RACE_BODY = "{\"name\":\"Backyard Test\",\"raceDate\":\"2026-10-03\","
        + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";

    private final Race r1 = backyardTest(RaceStatus.SETUP);
    private final Runner alice = runner(12L, r1, 6, "Alice", TOKEN);

    @BeforeEach
    void servicesReturnValidResults() {
        when(raceService.list()).thenReturn(List.of(r1,
            namedRace(2L, "Autre", LocalDate.of(2026, 11, 1), 5000, 3600, 0, RaceStatus.SETUP, null)));
        when(raceService.create(any())).thenReturn(r1);
        when(runnerService.register(1L, "Alice")).thenReturn(alice);
        when(raceBoardService.board(1L)).thenReturn(new RaceBoardView(r1, at("07:00:00"), 0, null, List.of()));
        when(raceBoardService.runnerDetail(12L)).thenReturn(new RunnerDetailView(12L, 1L, 6, "Alice",
            fr.backyard.domain.RunnerStatus.ACTIVE, null, null, 0, 0L, 0L, OptionalInt.empty(), false, List.of()));
        when(passageRecordingService.recordScan(anyString(), any()))
            .thenReturn(withId(scan(alice, 1, at("08:45:00")), 40L));
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String authorization) {
        return authorization == null ? request : request.header("Authorization", authorization);
    }

    // ----- CA46 / CA47 : 401 sans authentification -----

    @Test
    @DisplayName("CA46 - GET /api/admin/races sans Authorization : 401 UNAUTHENTICATED ProblemDetail, pas de WWW-Authenticate, service non appele")
    void ca46_adminWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/admin/races"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA46 - POST /api/admin/runners/7/dnf sans Authorization : 401, pas de WWW-Authenticate, ManualDnfService non appele")
    void ca46_dnfWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/admin/runners/7/dnf").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"VOLUNTARY\"}"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/runners/7/dnf"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(manualDnfService, never()).declareDnf(anyLong(), any());
    }

    @Test
    @DisplayName("CA46 - DELETE /api/admin/races/1 sans Authorization : 401, pas de WWW-Authenticate, suppression non appelee")
    void ca46_deleteWithoutAuthentication() throws Exception {
        mvc.perform(delete("/api/admin/races/1"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races/1"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(raceService, never()).delete(anyLong());
    }

    @Test
    @DisplayName("CA47 - POST /api/scan/passages sans Authorization : 401 UNAUTHENTICATED, pas de WWW-Authenticate, scan non enregistre")
    void ca47_scanWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/scan/passages").contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/scan/passages"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(passageRecordingService, never()).recordScan(anyString(), any());
    }

    // ----- CA48 : identifiants faux -----

    @Test
    @DisplayName("CA48 - mot de passe faux (admin-test:mauvais) : 401 UNAUTHENTICATED, detail sans 'mauvais' ni 'admin-test'")
    void ca48_wrongPassword() throws Exception {
        mvc.perform(get("/api/admin/races").header("Authorization", WRONG_PASSWORD))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races"))
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(jsonPath("$.detail").value(not(containsString("mauvais"))))
            .andExpect(jsonPath("$.detail").value(not(containsString("admin-test"))));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA48 - nom inconnu (inconnu:admin-secret) : 401 UNAUTHENTICATED, detail sans le nom ni le mot de passe")
    void ca48_unknownUser() throws Exception {
        mvc.perform(get("/api/admin/races").header("Authorization", UNKNOWN_USER))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races"))
            .andExpect(jsonPath("$.detail").value(not(containsString("inconnu:"))))
            .andExpect(jsonPath("$.detail").value(not(containsString("admin-secret"))));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA48 / RG6 - le detail ne dit pas si c'est le nom ou le mot de passe qui est faux (meme detail dans les deux cas)")
    void ca48_sameDetailForWrongUserOrPassword() throws Exception {
        String wrongPassword = mvc.perform(get("/api/admin/races").header("Authorization", WRONG_PASSWORD))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String unknownUser = mvc.perform(get("/api/admin/races").header("Authorization", UNKNOWN_USER))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(wrongPassword).isEqualTo(unknownUser);
    }

    // ----- CA49 : 403 SCANNER sur l'admin -----

    @Test
    @DisplayName("CA49 - GET /api/admin/races avec SCANNER : 403 ACCESS_DENIED ProblemDetail citant SCANNER, service non appele")
    void ca49_scannerOnAdmin() throws Exception {
        mvc.perform(get("/api/admin/races").header("Authorization", SCANNER))
            .andExpectAll(problem(403, "ACCESS_DENIED", "/api/admin/races"))
            .andExpect(jsonPath("$.detail").value(containsString("SCANNER")));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA49 - POST /api/admin/runners/7/reintegration avec SCANNER : 403, ReintegrationService non appele")
    void ca49_scannerCannotReintegrate() throws Exception {
        mvc.perform(post("/api/admin/runners/7/reintegration").header("Authorization", SCANNER))
            .andExpectAll(problem(403, "ACCESS_DENIED", "/api/admin/runners/7/reintegration"))
            .andExpect(jsonPath("$.detail").value(containsString("SCANNER")));

        verify(reintegrationService, never()).reintegrate(anyLong());
    }

    // ----- CA50 a CA52 : acces autorises -----

    @Test
    @DisplayName("CA50 - GET /api/admin/races avec ADMIN : 200, 2 elements, pas de Set-Cookie")
    void ca50_adminOnAdmin() throws Exception {
        mvc.perform(get("/api/admin/races").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA51 - POST /api/scan/passages avec SCANNER puis ADMIN : 200 dans les deux cas, pas de Set-Cookie")
    void ca51_scanAllowedForScannerAndAdmin() throws Exception {
        for (String account : List.of(SCANNER, ADMIN)) {
            mvc.perform(post("/api/scan/passages").header("Authorization", account)
                    .contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passageId").value(40))
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    @DisplayName("CA52 - public sans Authorization : liste 200, inscription 201, tableau de bord 200, detail coureur 200")
    void ca52_publicWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/public/races"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/public/races/1/registrations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\"}"))
            .andExpect(status().isCreated()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/public/races/1/board"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/public/runners/12"))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA52 - GET /api/public/races avec SCANNER ou ADMIN : 200")
    void ca52_publicWithValidCredentials() throws Exception {
        for (String account : List.of(SCANNER, ADMIN)) {
            mvc.perform(get("/api/public/races").header("Authorization", account))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    // ----- CA53 : en-tete Authorization invalide -----

    @Test
    @DisplayName("CA53 - GET /api/public/races avec 'Basic !!!pas-du-base64' : 401 UNAUTHENTICATED")
    void ca53_unreadableBasicHeader() throws Exception {
        mvc.perform(get("/api/public/races").header("Authorization", "Basic !!!pas-du-base64"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/public/races"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA53 / RG30 - GET /api/public/races avec un Basic sans ':' : 401 UNAUTHENTICATED")
    void ca53_basicHeaderWithoutColon() throws Exception {
        String noColon = "Basic " + java.util.Base64.getEncoder().encodeToString("admin-test".getBytes());
        mvc.perform(get("/api/public/races").header("Authorization", noColon))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/public/races"));
    }

    @Test
    @DisplayName("CA53 - GET /api/public/races avec des identifiants faux : 401")
    void ca53_wrongCredentialsOnPublic() throws Exception {
        mvc.perform(get("/api/public/races").header("Authorization", WRONG_PASSWORD))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/public/races"));

        verify(raceService, never()).list();
    }

    @Test
    @DisplayName("CA53 - 'Authorization: Bearer abc' ignore : public 200, admin 401")
    void ca53_bearerHeaderIsIgnored() throws Exception {
        mvc.perform(get("/api/public/races").header("Authorization", "Bearer abc"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/admin/races").header("Authorization", "Bearer abc"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    // ----- CA54 : sans etat, sans CSRF -----

    @Test
    @DisplayName("CA54 - POST /api/admin/races avec ADMIN sans jeton CSRF : 201, pas de Set-Cookie")
    void ca54_noCsrfRequired() throws Exception {
        mvc.perform(post("/api/admin/races").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(RACE_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA54 - une requete ADMIN puis une requete sans Authorization (meme session eventuelle) : la seconde recoit 401")
    void ca54_noSessionIsKept() throws Exception {
        MvcResult first = mvc.perform(get("/api/admin/races").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn();
        HttpSession session = first.getRequest().getSession(false);
        if (session != null) {
            assertThat(java.util.Collections.list(session.getAttributeNames()))
                .as("aucun contexte de securite conserve en session").isEmpty();
        }

        MockHttpServletRequestBuilder second = get("/api/admin/races");
        if (session instanceof MockHttpSession mockSession) {
            second = second.session(mockSession);
        }
        mvc.perform(second)
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races"));
    }

    // ----- CA55 : refus par defaut -----

    @Test
    @DisplayName("CA55 - GET /api/autre : 401 sans authentification, 403 ACCESS_DENIED avec ADMIN")
    void ca55_denyByDefault() throws Exception {
        mvc.perform(get("/api/autre"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/autre"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/autre").header("Authorization", ADMIN))
            .andExpectAll(problem(403, "ACCESS_DENIED", "/api/autre"));
    }

    @Test
    @DisplayName("CA55 - GET /api/admin/inconnu : 401 anonyme, 403 SCANNER, 404 ADMIN")
    void ca55_unknownAdminPath() throws Exception {
        mvc.perform(withAuth(get("/api/admin/inconnu"), null))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/inconnu"));
        mvc.perform(withAuth(get("/api/admin/inconnu"), SCANNER))
            .andExpectAll(problem(403, "ACCESS_DENIED", "/api/admin/inconnu"));
        mvc.perform(withAuth(get("/api/admin/inconnu"), ADMIN))
            .andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/admin/inconnu"));
    }

    @Test
    @DisplayName("CA55 / RG6 - methode non supportee sous /api/admin : 401 anonyme, 403 SCANNER (securite avant le 405)")
    void ca55_unsupportedMethodUnderAdminIsSecuredFirst() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/races/1"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/api/admin/races/1"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/races/1")
                .header("Authorization", SCANNER))
            .andExpectAll(problem(403, "ACCESS_DENIED", "/api/admin/races/1"));
    }

    // ----- CA56 : pas de page de connexion -----

    @Test
    @DisplayName("CA56 - GET /login : 401 (aucune page de connexion generee)")
    void ca56_noLoginPage() throws Exception {
        mvc.perform(get("/login"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/login"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("CA56 / RG31 - GET /logout : pas de page de deconnexion (401)")
    void ca56_noLogoutPage() throws Exception {
        mvc.perform(get("/logout"))
            .andExpectAll(problem(401, "UNAUTHENTICATED", "/logout"));
    }
}
