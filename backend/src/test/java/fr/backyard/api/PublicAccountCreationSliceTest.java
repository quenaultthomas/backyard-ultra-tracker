package fr.backyard.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.config.SecurityConfig;
import fr.backyard.domain.Account;
import fr.backyard.domain.Pseudo;
import fr.backyard.service.AccountCredentials;
import fr.backyard.service.AccountRegistrationsView;
import fr.backyard.service.AccountService;
import fr.backyard.service.ManualDnfService;
import fr.backyard.service.PassageRecordingService;
import fr.backyard.service.RaceBoardService;
import fr.backyard.service.RaceService;
import fr.backyard.service.ReintegrationService;
import fr.backyard.service.RunnerService;
import fr.backyard.service.SessionService;
import fr.backyard.service.SessionView;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.testsupport.AccountFakes;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 7 [slice] : E26 {@code POST /api/public/accounts} (RG5), CA1 (partie HTTP), CA2, CA5 et CA18
 * (journaux de la couche API). Securite reelle, vrais en-tetes Authorization, services mockes. Le message du 409
 * est celui produit par le vrai {@link AccountService#create} (RG5 : texte reutilise tel quel, aucune seconde
 * regle), obtenu sur des depots en memoire.
 */
@Tag("INC-7")
@WebMvcTest
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class PublicAccountCreationSliceTest {

    private static final String URL = "/api/public/accounts";
    private static final String PASSWORD = "motdepasse-9";
    private static final PasswordEncoder TEST_ENCODER = new BCryptPasswordEncoder(4);
    private static final String LIEVRE_HASH = TEST_ENCODER.encode("motdepasse-1");

    private static final String ADMIN = basic("admin-test", "admin-secret");
    private static final String SCANNER = basic("scanner-test", "scanner-secret");
    private static final String LIEVRE = basic("Lievre", "motdepasse-1");
    private static final String ADMIN_BAD_PASSWORD = basic("admin-test", "mauvais");

    private static final String CONFLICT_DETAIL =
        "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec.";

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

    private final Logger appLogger = (Logger) LoggerFactory.getLogger("fr.backyard");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Account account(long id, String pseudo) {
        Account account = new Account(pseudo, "$2a$12$" + "x".repeat(53));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @BeforeEach
    void stubsAndLogCapture() {
        when(accountService.findCredentials(anyString())).thenAnswer(inv ->
            "lievre".equals(Pseudo.normalize(inv.getArgument(0)))
                ? Optional.of(new AccountCredentials(5L, "lievre", LIEVRE_HASH))
                : Optional.empty());
        when(accountService.registrations(anyLong())).thenReturn(new AccountRegistrationsView("lievre", List.of()));
        when(sessionService.describe(anyString(), any())).thenAnswer(inv -> new SessionView(inv.getArgument(0),
            "SCANNER", Instant.parse("2026-10-03T10:00:00Z")));
        when(accountService.create(anyString(), anyString())).thenAnswer(inv ->
            account(701L, Pseudo.normalize(inv.getArgument(0))));
        previousLevel = appLogger.getLevel();
        appLogger.setLevel(Level.TRACE);
        logs.start();
        appLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        appLogger.detachAppender(logs);
        appLogger.setLevel(previousLevel);
    }

    private ResultActions perform(String authorization, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private static String body(String pseudo, String password) {
        return "{\"pseudo\":\"" + pseudo + "\",\"password\":\"" + password + "\"}";
    }

    private void expectUnauthorized(ResultActions actions, String detail) throws Exception {
        actions.andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.detail").value(detail))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    // ----- CA1 (partie HTTP) -----

    @Test
    @DisplayName("CA1 - E26 anonyme {\"  Nouveau-1 \"} : 201, saisie transmise telle quelle au service, corps strict {pseudo: nouveau-1}")
    void ca1_createReturns201WithStoredPseudoOnly() throws Exception {
        perform(null, body("  Nouveau-1 ", PASSWORD))
            .andExpect(status().isCreated())
            .andExpect(content().json("{\"pseudo\":\"nouveau-1\"}", JsonCompareMode.STRICT))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.accountId").doesNotExist())
            .andExpect(jsonPath("$.id").doesNotExist())
            .andExpect(header().doesNotExist("Set-Cookie"));

        verify(accountService).create("  Nouveau-1 ", PASSWORD);
    }

    @Test
    @DisplayName("CA1 - la reponse 201 ne contient ni le mot de passe, ni un hash BCrypt, ni la valeur du hash stocke")
    void ca1_responseCarriesNoSecret() throws Exception {
        String response = perform(null, body("nouveau-1", PASSWORD))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).doesNotContain(PASSWORD).doesNotContain("$2").doesNotContain("x".repeat(20));
    }

    @Test
    @DisplayName("CA1 - E26 ne cree ni coureur ni inscription : seul AccountService.create est appele sur le service de course")
    void ca1_noRunnerNorRegistrationIsInvolved() throws Exception {
        perform(null, body("nouveau-1", PASSWORD)).andExpect(status().isCreated());

        verify(runnerService, never()).register(anyLong(), any(), any());
        verify(runnerService, never()).registerAccount(anyLong(), anyLong());
        verify(accountService).create(anyString(), anyString());
    }

    // ----- CA2 -----

    @ParameterizedTest(name = "CA2 - pseudo refuse ''{0}'' : 400 VALIDATION_FAILED sur pseudo seul, service non appele")
    @ValueSource(strings = {"ab", "a b c", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "élan", ""})
    void ca2_refusedPseudos(String pseudo) throws Exception {
        perform(null, body(pseudo, PASSWORD))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("pseudo")));

        verify(accountService, never()).create(any(), any());
    }

    @ParameterizedTest(name = "CA2 - mot de passe refuse (longueur {0}) : 400 VALIDATION_FAILED sur password seul, service non appele")
    @ValueSource(strings = {"court12", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ""})
    void ca2_refusedPasswords(String password) throws Exception {
        perform(null, body("nouveau-1", password))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("password")));

        verify(accountService, never()).create(any(), any());
    }

    @Test
    @DisplayName("CA2 - mot de passe de 73 octets (73 caracteres ASCII) : 400 ; 72 octets : accepte")
    void ca2_passwordByteLimitIsExact() throws Exception {
        perform(null, body("nouveau-1", "a".repeat(73)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("password")));
        verify(accountService, never()).create(any(), any());

        perform(null, body("nouveau-1", "a".repeat(72))).andExpect(status().isCreated());
        verify(accountService).create("nouveau-1", "a".repeat(72));
    }

    @Test
    @DisplayName("CA2 - corps {} : 400 sur pseudo et password ; corps absent ou illisible : 400 ; service jamais appele")
    void ca2_emptyOrMissingBody() throws Exception {
        perform(null, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("pseudo", "password")));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        perform(null, "pas du json").andExpect(status().isBadRequest());

        verify(accountService, never()).create(any(), any());
    }

    // ----- 409 (RG5, CL5) -----

    @ParameterizedTest(name = "CA3 (partie HTTP) - pseudo ''{0}'' deja pris : 409 BUSINESS_CONFLICT, detail du vrai service")
    @ValueSource(strings = {"Lievre", "LIEVRE", "lievre"})
    void ca3_conflictCarriesTheRealServiceMessage(String pseudo) throws Exception {
        FakeRepositories fakes = new FakeRepositories();
        AccountFakes accountFakes = new AccountFakes(fakes);
        accountFakes.withAccounts(AccountFakes.account(5L, "lievre", LIEVRE_HASH));
        AccountService real = new AccountService(accountFakes.accountRepository, fakes.runnerRepository,
            TEST_ENCODER);
        when(accountService.create(anyString(), anyString())).thenAnswer(inv ->
            real.create(inv.getArgument(0), inv.getArgument(1)));

        perform(null, body(pseudo, PASSWORD))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(CONFLICT_DETAIL));
    }

    // ----- CA5 : matrice d'acces de E26 -----

    @Test
    @DisplayName("CA5 - E26 : anonyme, SCANNER et ADMIN obtiennent 201")
    void ca5_publicProfilesAreAllowed() throws Exception {
        for (String authorization : new String[] {null, SCANNER, ADMIN}) {
            perform(authorization, body("nouveau-1", PASSWORD)).andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("CA5 - E26 avec les identifiants d'un compte pseudo : 401 « Identifiants invalides » sans WWW-Authenticate, service non appele")
    void ca5_runnerCredentialsAreRejected() throws Exception {
        expectUnauthorized(perform(LIEVRE, body("nouveau-1", PASSWORD)), "Identifiants invalides");

        verify(accountService, never()).create(any(), any());
    }

    @Test
    @DisplayName("CA5 - E26 avec admin-test et un mauvais mot de passe : 401, service non appele")
    void ca5_badStaffPasswordIsRejected() throws Exception {
        expectUnauthorized(perform(ADMIN_BAD_PASSWORD, body("nouveau-1", PASSWORD)), "Identifiants invalides");

        verify(accountService, never()).create(any(), any());
    }

    @Test
    @DisplayName("CA5 - E26 n'accepte que POST : GET renvoie 405 METHOD_NOT_ALLOWED et ne cree rien")
    void ca5_onlyPostCreates() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        verify(accountService, never()).create(any(), any());
    }

    // ----- CA18 (couche API) -----

    @Test
    @DisplayName("CA18 - creation, conflit 409 et validation 400 de E26 : aucune ligne de journal ne contient pseudo, mot de passe ni hash")
    void ca18_noSecretNorPseudoInLogs() throws Exception {
        perform(null, body("nouveau-1", PASSWORD)).andExpect(status().isCreated());
        when(accountService.create(anyString(), anyString())).thenThrow(new BusinessConflictException(CONFLICT_DETAIL));
        perform(null, body("nouveau-1", PASSWORD)).andExpect(status().isConflict());
        perform(null, body("ab", PASSWORD)).andExpect(status().isBadRequest());
        perform(null, body("nouveau-1", "court12")).andExpect(status().isBadRequest());

        assertThat(logs.list).as("l'API journalise au moins le 409 et les 400 (sinon le controle passerait a vide)")
            .isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(line).doesNotContain("nouveau-1").doesNotContain("Nouveau-1")
                .doesNotContain(PASSWORD).doesNotContain("court12").doesNotContain("$2");
        }
    }
}
