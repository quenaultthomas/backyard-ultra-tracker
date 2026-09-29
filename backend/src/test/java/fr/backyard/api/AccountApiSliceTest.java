package fr.backyard.api;

import fr.backyard.config.SecurityConfig;
import fr.backyard.domain.Account;
import fr.backyard.domain.Pseudo;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.AccountCredentials;
import fr.backyard.service.AccountRegistrationView;
import fr.backyard.service.AccountRegistrationsView;
import fr.backyard.service.AccountService;
import fr.backyard.service.AccountSummaryView;
import fr.backyard.service.ManualDnfService;
import fr.backyard.service.PassageRecordingService;
import fr.backyard.service.RaceBoardService;
import fr.backyard.service.RaceService;
import fr.backyard.service.ReintegrationService;
import fr.backyard.service.RunnerService;
import fr.backyard.service.SessionService;
import fr.backyard.service.SessionView;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 5 [slice] : endpoints E3 (modifie), E20 a E25, matrice d'acces par prefixe d'URL (RG9, RG10) et
 * validation (RG2, RG3). Securite reelle, vrais en-tetes Authorization, services mockes. Le referentiel des comptes
 * coureurs est simule par {@link AccountService#findCredentials(String)}, qui normalise le pseudo presente comme
 * le fait le service reel.
 */
@Tag("INC-5")
@WebMvcTest
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AccountApiSliceTest {

    private static final PasswordEncoder TEST_ENCODER = new BCryptPasswordEncoder(4);
    private static final String LIEVRE_HASH = TEST_ENCODER.encode("motdepasse-1");
    private static final String ADMIN_TEST_PSEUDO_HASH = TEST_ENCODER.encode("motdepasse-1");
    private static final String SCANNER_TEST_PSEUDO_HASH = TEST_ENCODER.encode("scanner-secret");

    private static final String ADMIN = basic("admin-test", "admin-secret");
    private static final String SCANNER = basic("scanner-test", "scanner-secret");
    private static final String LIEVRE = basic("Lievre", "motdepasse-1");
    private static final String TOKEN = "3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RaceService raceService;
    @MockitoBean
    RunnerService runnerService;
    @MockitoBean
    RaceBoardService raceBoardService;
    @MockitoBean
    PassageRecordingService passageRecordingService;
    @MockitoBean
    ManualDnfService manualDnfService;
    @MockitoBean
    ReintegrationService reintegrationService;
    @MockitoBean
    SessionService sessionService;
    @MockitoBean
    AccountService accountService;

    private final Race r1 = race(1L, "Backyard Test", LocalDate.of(2026, 10, 3));
    private final Race r2 = race(2L, "Backyard Automne", LocalDate.of(2026, 11, 7));
    private final Account lievre = account(5L, "lievre");

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Race race(long id, String name, LocalDate date) {
        Race race = new Race(name, date, 6706, 3600, 50);
        ReflectionTestUtils.setField(race, "id", id);
        return race;
    }

    private static Account account(long id, String pseudo) {
        Account account = new Account(pseudo, "$2a$12$" + "x".repeat(53));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private static Runner runner(long id, Race race, int bib, Account account) {
        Runner runner = new Runner(race, bib, account, TOKEN);
        ReflectionTestUtils.setField(runner, "id", id);
        return runner;
    }

    /** Comptes coureurs du referentiel simule, par pseudo stocke : lievre ; CA22 ajoute admin-test et scanner-test. */
    private final Map<String, AccountCredentials> known = new HashMap<>(
        Map.of("lievre", new AccountCredentials(5L, "lievre", LIEVRE_HASH)));

    @BeforeEach
    void runnerAccounts() {
        when(accountService.findCredentials(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(known.get(Pseudo.normalize(inv.getArgument(0)))));
        when(accountService.registrations(anyLong()))
            .thenAnswer(inv -> new AccountRegistrationsView(inv.getArgument(0).equals(5L) ? "lievre" : "autre",
                List.of()));
        when(raceService.list()).thenReturn(List.of(r1));
        when(sessionService.describe(anyString(), any())).thenAnswer(inv -> new SessionView(inv.getArgument(0),
            "SCANNER", Instant.parse("2026-10-03T10:00:00Z")));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void expectUnauthorized(ResultActions actions, String detail) throws Exception {
        actions.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.detail").value(detail))
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // ----- E3 (RG7, RG2, RG3) -----

    @Test
    @DisplayName("CA6 - E3 {pseudo: Lievre} : 201, service appele avec la saisie telle quelle, reponse avec pseudo et name lievre")
    void ca6_registrationWithAccount() throws Exception {
        when(runnerService.register(1L, "Lievre", "motdepasse-1")).thenReturn(runner(12L, r1, 1, lievre));

        perform(json(post("/api/public/races/1/registrations"), "{\"pseudo\":\"Lievre\",\"password\":\"motdepasse-1\"}"),
            null)
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/public/runners/12"))
            .andExpect(content().json("{\"runnerId\":12,\"raceId\":1,\"bib\":1,\"pseudo\":\"lievre\",\"name\":\"lievre\","
                + "\"qrToken\":\"" + TOKEN + "\"}", JsonCompareMode.STRICT))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA11 - ancien corps {name: Alice} : 400 VALIDATION_FAILED sur pseudo et password, service non appele")
    void ca11_oldBodyIsRejected() throws Exception {
        perform(json(post("/api/public/races/1/registrations"), "{\"name\":\"Alice\"}"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("pseudo", "password")));

        verify(runnerService, never()).register(anyLong(), any(), any());
    }

    @ParameterizedTest(name = "CA2 - pseudo accepte ''{0}'' : 201, transmis tel que saisi")
    @ValueSource(strings = {"Lievre_42", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "  Lievre  ", "LIEVRE"})
    void ca2_acceptedPseudos(String pseudo) throws Exception {
        when(runnerService.register(1L, pseudo, "motdepasse-1")).thenReturn(runner(12L, r1, 1, lievre));

        perform(json(post("/api/public/races/1/registrations"),
            "{\"pseudo\":\"" + pseudo + "\",\"password\":\"motdepasse-1\"}"), null)
            .andExpect(status().isCreated());

        verify(runnerService).register(1L, pseudo, "motdepasse-1");
    }

    @ParameterizedTest(name = "CA2 - pseudo refuse ''{0}'' : 400 VALIDATION_FAILED sur pseudo seul, service non appele")
    @ValueSource(strings = {"ab", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Jean Dupont", "élan", ""})
    void ca2_refusedPseudos(String pseudo) throws Exception {
        perform(json(post("/api/public/races/1/registrations"),
            "{\"pseudo\":\"" + pseudo + "\",\"password\":\"motdepasse-1\"}"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("pseudo")));

        verify(runnerService, never()).register(anyLong(), any(), any());
    }

    @ParameterizedTest(name = "CA3 - mot de passe refuse (longueur {0}) sur E3, E22 et E23 : 400 VALIDATION_FAILED")
    @ValueSource(strings = {"court12", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "ééééééééééééééééééééééééééééééééééééé"})
    void ca3_refusedPasswords(String password) throws Exception {
        perform(json(post("/api/public/races/1/registrations"),
            "{\"pseudo\":\"Lievre\",\"password\":\"" + password + "\"}"), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("password")));
        perform(json(put("/api/admin/accounts/5/password"), "{\"newPassword\":\"" + password + "\"}"), ADMIN)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("newPassword")));
        perform(json(put("/api/account/password"), "{\"newPassword\":\"" + password + "\"}"), LIEVRE)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("newPassword")));

        verify(runnerService, never()).register(anyLong(), any(), any());
        verify(accountService, never()).resetPassword(anyLong(), any());
        verify(accountService, never()).changePassword(anyLong(), any());
    }

    @ParameterizedTest(name = "CA3 - mot de passe accepte (longueur {0}) sur E3, E22 et E23")
    @ValueSource(strings = {"huitcar8", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void ca3_acceptedPasswords(String password) throws Exception {
        when(runnerService.register(1L, "Lievre", password)).thenReturn(runner(12L, r1, 1, lievre));

        perform(json(post("/api/public/races/1/registrations"),
            "{\"pseudo\":\"Lievre\",\"password\":\"" + password + "\"}"), null)
            .andExpect(status().isCreated());
        perform(json(put("/api/admin/accounts/5/password"), "{\"newPassword\":\"" + password + "\"}"), ADMIN)
            .andExpect(status().isNoContent());
        perform(json(put("/api/account/password"), "{\"newPassword\":\"" + password + "\"}"), LIEVRE)
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("CA7 / CA10 - pseudo deja pris : 409 BUSINESS_CONFLICT avec le detail du service")
    void ca7_pseudoTaken() throws Exception {
        when(runnerService.register(2L, "LIEVRE", "motdepasse-1")).thenThrow(new BusinessConflictException(
            "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec."));

        perform(json(post("/api/public/races/2/registrations"), "{\"pseudo\":\"LIEVRE\",\"password\":\"motdepasse-1\"}"),
            null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(
                "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec."));
    }

    // ----- authentification et matrice (RG9, RG10) -----

    @ParameterizedTest(name = "CA12 - identifiants {0} / motdepasse-1 : E21 200, pseudo lievre")
    @ValueSource(strings = {"Lievre", "lievre", "LIEVRE"})
    void ca12_anyCaseAuthenticates(String presented) throws Exception {
        perform(get("/api/account/me"), basic(presented, "motdepasse-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("lievre"))
            .andExpect(header().doesNotExist("Set-Cookie"));

        verify(accountService).registrations(5L);
    }

    @Test
    @DisplayName("CA12 - echecs (mot de passe faux, mot de passe en majuscules, compte inconnu) : 401 « Identifiants invalides »")
    void ca12_failures() throws Exception {
        expectUnauthorized(perform(get("/api/account/me"), basic("Lievre", "motdepasse-2")), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/account/me"), basic("Lievre", "MOTDEPASSE-1")), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/account/me"), basic("Inconnu", "motdepasse-1")), "Identifiants invalides");

        verify(accountService, never()).registrations(anyLong());
    }

    @Test
    @DisplayName("CA13 - E21 : anonyme 401 « Authentification requise », RUNNER 200, SCANNER et ADMIN 401 « Identifiants invalides »")
    void ca13_accountEndpointMatrix() throws Exception {
        expectUnauthorized(perform(get("/api/account/me"), null), "Authentification requise");
        perform(get("/api/account/me"), LIEVRE).andExpect(status().isOk());
        expectUnauthorized(perform(get("/api/account/me"), SCANNER), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/account/me"), ADMIN), "Identifiants invalides");
    }

    @Test
    @DisplayName("CA13 - RUNNER hors /api/account : scan, admin et public 401 ; public sans en-tete 200")
    void ca13_runnerCredentialsOutsideAccountPaths() throws Exception {
        expectUnauthorized(perform(get("/api/scan/me"), LIEVRE), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/admin/races"), LIEVRE), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/public/races"), LIEVRE), "Identifiants invalides");
        perform(get("/api/public/races"), null).andExpect(status().isOk());

        verify(raceService).list();
    }

    @Test
    @DisplayName("CA13 - SCANNER : scan/me 200, admin 403, public 200 ; ADMIN : admin 200")
    void ca13_staffUnchanged() throws Exception {
        perform(get("/api/scan/me"), SCANNER).andExpect(status().isOk());
        perform(get("/api/admin/races"), SCANNER).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        perform(get("/api/public/races"), SCANNER).andExpect(status().isOk());
        perform(get("/api/admin/races"), ADMIN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA22 - pseudo admin-test : 401 sur admin et scan, E21 200 ; le vrai ADMIN garde son acces, E21 401")
    void ca22_pseudoEqualToAdminAccount() throws Exception {
        known.put("admin-test", new AccountCredentials(8L, "admin-test", ADMIN_TEST_PSEUDO_HASH));
        String runnerAdminTest = basic("admin-test", "motdepasse-1");

        expectUnauthorized(perform(get("/api/admin/races"), runnerAdminTest), "Identifiants invalides");
        expectUnauthorized(perform(get("/api/scan/me"), runnerAdminTest), "Identifiants invalides");
        perform(get("/api/account/me"), runnerAdminTest).andExpect(status().isOk());

        perform(get("/api/admin/races"), ADMIN).andExpect(status().isOk());
        expectUnauthorized(perform(get("/api/account/me"), ADMIN), "Identifiants invalides");
    }

    @Test
    @DisplayName("CA22 - pseudo scanner-test avec le mot de passe du SCANNER : E21 200 (compte coureur), scan/me 200 role SCANNER")
    void ca22_pseudoEqualToScannerAccountWithSamePassword() throws Exception {
        known.put("scanner-test", new AccountCredentials(9L, "scanner-test", SCANNER_TEST_PSEUDO_HASH));
        perform(get("/api/account/me"), SCANNER).andExpect(status().isOk());
        verify(accountService).registrations(9L);

        perform(get("/api/scan/me"), SCANNER)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("SCANNER"));
        verify(sessionService).describe(eq("scanner-test"), argThat(authorities ->
            authorities.contains("ROLE_SCANNER") && !authorities.contains("ROLE_RUNNER")));
    }

    // ----- E20, E21, E23 (compte coureur) -----

    @Test
    @DisplayName("CA9 / CA10 - E20 : 201, compte de l'authentification, meme corps que E3")
    void ca9_registerAuthenticatedAccount() throws Exception {
        when(runnerService.registerAccount(2L, 5L)).thenReturn(runner(13L, r2, 1, lievre));

        perform(post("/api/account/races/2/registrations"), LIEVRE)
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/public/runners/13"))
            .andExpect(content().json("{\"runnerId\":13,\"raceId\":2,\"bib\":1,\"pseudo\":\"lievre\",\"name\":\"lievre\","
                + "\"qrToken\":\"" + TOKEN + "\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("CA9 - E20 deja inscrit : 409 « Déjà inscrit à cette course » ; course introuvable : 404")
    void ca9_registrationErrors() throws Exception {
        when(runnerService.registerAccount(2L, 5L)).thenThrow(new BusinessConflictException("Déjà inscrit à cette course"));
        when(runnerService.registerAccount(99L, 5L)).thenThrow(new ResourceNotFoundException("Course introuvable : id 99"));

        perform(post("/api/account/races/2/registrations"), LIEVRE)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Déjà inscrit à cette course"));
        perform(post("/api/account/races/99/registrations"), LIEVRE)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("CA14 - E21 : pseudo et inscriptions avec qr_token, dans l'ordre du service")
    void ca14_myRegistrations() throws Exception {
        when(accountService.registrations(5L)).thenReturn(new AccountRegistrationsView("lievre", List.of(
            new AccountRegistrationView(1L, "Backyard Test", LocalDate.of(2026, 10, 3), RaceStatus.SETUP, 21L, 3,
                RunnerStatus.ACTIVE, "tok-r1"),
            new AccountRegistrationView(2L, "Backyard Automne", LocalDate.of(2026, 11, 7), RaceStatus.SETUP, 20L, 1,
                RunnerStatus.ACTIVE, "tok-r2"))));

        perform(get("/api/account/me"), LIEVRE)
            .andExpect(status().isOk())
            .andExpect(content().json("{\"pseudo\":\"lievre\",\"registrations\":["
                + "{\"raceId\":1,\"raceName\":\"Backyard Test\",\"raceDate\":\"2026-10-03\",\"raceStatus\":\"SETUP\","
                + "\"runnerId\":21,\"bib\":3,\"status\":\"ACTIVE\",\"qrToken\":\"tok-r1\"},"
                + "{\"raceId\":2,\"raceName\":\"Backyard Automne\",\"raceDate\":\"2026-11-07\",\"raceStatus\":\"SETUP\","
                + "\"runnerId\":20,\"bib\":1,\"status\":\"ACTIVE\",\"qrToken\":\"tok-r2\"}]}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("CA32 - E23 : 204 sans corps pour le compte authentifie ; anonyme 401, SCANNER et ADMIN 401")
    void ca32_changePassword() throws Exception {
        MvcResult result = perform(json(put("/api/account/password"), "{\"newPassword\":\"nouveau-mdp-43\"}"), LIEVRE)
            .andExpect(status().isNoContent())
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(accountService).changePassword(5L, "nouveau-mdp-43");

        expectUnauthorized(perform(json(put("/api/account/password"), "{\"newPassword\":\"nouveau-mdp-43\"}"), null),
            "Authentification requise");
        expectUnauthorized(perform(json(put("/api/account/password"), "{\"newPassword\":\"nouveau-mdp-43\"}"), SCANNER),
            "Identifiants invalides");
        expectUnauthorized(perform(json(put("/api/account/password"), "{\"newPassword\":\"nouveau-mdp-43\"}"), ADMIN),
            "Identifiants invalides");
    }

    // ----- E22, E24, E25 (admin) -----

    @Test
    @DisplayName("CA17 / CA18 - E22 : ADMIN 204 sans corps ; compte 99 404 ; anonyme 401, SCANNER 403, RUNNER 401")
    void ca18_resetPassword() throws Exception {
        String body = "{\"newPassword\":\"nouveau-mdp-42\"}";
        MvcResult result = perform(json(put("/api/admin/accounts/5/password"), body), ADMIN)
            .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(accountService).resetPassword(5L, "nouveau-mdp-42");

        doThrow(new ResourceNotFoundException("Compte introuvable : id 99"))
            .when(accountService).resetPassword(99L, "nouveau-mdp-42");
        perform(json(put("/api/admin/accounts/99/password"), body), ADMIN)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        expectUnauthorized(perform(json(put("/api/admin/accounts/5/password"), body), null), "Authentification requise");
        perform(json(put("/api/admin/accounts/5/password"), body), SCANNER).andExpect(status().isForbidden());
        expectUnauthorized(perform(json(put("/api/admin/accounts/5/password"), body), LIEVRE), "Identifiants invalides");
    }

    @Test
    @DisplayName("CA33 - E24 : ADMIN 204 ; second appel 404 ; anonyme 401, SCANNER 403, RUNNER 401")
    void ca33_deleteAccount() throws Exception {
        perform(delete("/api/admin/accounts/5"), ADMIN).andExpect(status().isNoContent());
        verify(accountService).delete(5L);

        doThrow(new ResourceNotFoundException("Compte introuvable : id 5")).when(accountService).delete(5L);
        perform(delete("/api/admin/accounts/5"), ADMIN).andExpect(status().isNotFound());

        expectUnauthorized(perform(delete("/api/admin/accounts/5"), null), "Authentification requise");
        perform(delete("/api/admin/accounts/5"), SCANNER).andExpect(status().isForbidden());
        expectUnauthorized(perform(delete("/api/admin/accounts/5"), LIEVRE), "Identifiants invalides");
    }

    @Test
    @DisplayName("CA42 - E25 : liste transmise au service, elements a exactement 3 proprietes ; acces anonyme 401, SCANNER 403, RUNNER 401")
    void ca42_listAccounts() throws Exception {
        when(accountService.search("LIE")).thenReturn(List.of(new AccountSummaryView(5L, "lievre", 2),
            new AccountSummaryView(6L, "oublie", 0)));
        when(accountService.search(null)).thenReturn(List.of());

        perform(get("/api/admin/accounts").param("pseudo", "LIE"), ADMIN)
            .andExpect(status().isOk())
            .andExpect(content().json("[{\"accountId\":5,\"pseudo\":\"lievre\",\"runnerCount\":2},"
                + "{\"accountId\":6,\"pseudo\":\"oublie\",\"runnerCount\":0}]", JsonCompareMode.STRICT));
        perform(get("/api/admin/accounts"), ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        verify(accountService).search(null);

        expectUnauthorized(perform(get("/api/admin/accounts"), null), "Authentification requise");
        perform(get("/api/admin/accounts"), SCANNER).andExpect(status().isForbidden());
        expectUnauthorized(perform(get("/api/admin/accounts"), LIEVRE), "Identifiants invalides");
    }

    // ----- E13 (RG12), E15 (RG18), CA5 -----

    @Test
    @DisplayName("CA15 - E13 : accountId et pseudo pour un coureur lie, null sans compte ; name = nom affiche")
    void ca15_adminRunnerList() throws Exception {
        when(runnerService.listByRace(1L)).thenReturn(List.of(runner(12L, r1, 1, lievre), runner(13L, r1, 2, null)));

        perform(get("/api/admin/races/1/runners"), ADMIN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accountId").value(5))
            .andExpect(jsonPath("$[0].pseudo").value("lievre"))
            .andExpect(jsonPath("$[0].name").value("lievre"))
            .andExpect(jsonPath("$[1].accountId").isEmpty())
            .andExpect(jsonPath("$[1].pseudo").isEmpty())
            .andExpect(jsonPath("$[1].name").value("Coureur n°2"));
    }

    @Test
    @DisplayName("CA30 - E15 avec {bib: 2, name: Autre} : seul le dossard est transmis, name ignore")
    void ca30_updateIgnoresName() throws Exception {
        when(runnerService.update(12L, 2)).thenReturn(runner(12L, r1, 2, lievre));

        perform(json(put("/api/admin/runners/12"), "{\"bib\":2,\"name\":\"Autre\"}"), ADMIN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bib").value(2))
            .andExpect(jsonPath("$.name").value("lievre"));

        verify(runnerService).update(12L, 2);
    }

    @Test
    @DisplayName("CA5 - aucune reponse E3, E20, E21, E13, E25 ne contient password, passwordHash, newPassword ni hash BCrypt")
    void ca5_noSecretInResponses() throws Exception {
        when(runnerService.register(1L, "Lievre", "motdepasse-1")).thenReturn(runner(12L, r1, 1, lievre));
        when(runnerService.registerAccount(2L, 5L)).thenReturn(runner(13L, r2, 1, lievre));
        when(runnerService.listByRace(1L)).thenReturn(List.of(runner(12L, r1, 1, lievre)));
        when(accountService.search(null)).thenReturn(List.of(new AccountSummaryView(5L, "lievre", 1)));

        List<String> bodies = List.of(
            perform(json(post("/api/public/races/1/registrations"),
                "{\"pseudo\":\"Lievre\",\"password\":\"motdepasse-1\"}"), null).andReturn()
                .getResponse().getContentAsString(),
            perform(post("/api/account/races/2/registrations"), LIEVRE).andReturn().getResponse().getContentAsString(),
            perform(get("/api/account/me"), LIEVRE).andReturn().getResponse().getContentAsString(),
            perform(get("/api/admin/races/1/runners"), ADMIN).andReturn().getResponse().getContentAsString(),
            perform(get("/api/admin/accounts"), ADMIN).andReturn().getResponse().getContentAsString());

        assertThat(bodies).allSatisfy(body -> assertThat(body)
            .isNotEmpty()
            .doesNotContain("\"password\"").doesNotContain("passwordHash").doesNotContain("newPassword")
            .doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$").doesNotContain("motdepasse"));
    }
}
