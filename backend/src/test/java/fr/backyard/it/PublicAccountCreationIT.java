package fr.backyard.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 7 [IT] : E26 {@code POST /api/public/accounts} sur la vraie chaine (HTTP Basic reel, securite
 * reelle, {@code AccountService} reel, JPA, H2 en mode PostgreSQL, schema Flyway). Complete le test de slice
 * {@code PublicAccountCreationSliceTest} (services mockes) : CA1 (volet base), CA3 (comptage), CA4 (concurrence),
 * CA5 (matrice d'acces reelle), CA6, CA7 et CA18 (journaux de bout en bout). RT1 : H2, pas de PostgreSQL reel.
 */
@Tag("INC-7")
class PublicAccountCreationIT extends AbstractApiIT {

    private static final String URL = "/api/public/accounts";
    private static final String PASSWORD = "motdepasse-9";
    private static final String CONFLICT_DETAIL_LIEVRE =
        "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec.";

    @Autowired
    JdbcTemplate jdbc;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger applicationLogger = (Logger) LoggerFactory.getLogger("fr.backyard");
    private Level applicationLevelBefore;

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String body(String pseudo, String password) {
        return "{\"pseudo\":\"" + pseudo + "\",\"password\":\"" + password + "\"}";
    }

    @BeforeEach
    void startCapturingLogs() {
        wipe();
        applicationLevelBefore = applicationLogger.getLevel();
        applicationLogger.setLevel(Level.TRACE);
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogsAndWipe() {
        rootLogger.detachAppender(logs);
        applicationLogger.setLevel(applicationLevelBefore);
        wipe();
    }

    private void wipe() {
        jdbc.update("DELETE FROM passage");
        jdbc.update("DELETE FROM runner");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM race");
    }

    private ResultActions create(String pseudo, String password, String authorization) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body(pseudo, password));
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String bodyOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @Tag("INC7-CA1")
    @DisplayName("CA1 (base) - E26 « Nouveau-1 » : 201 {pseudo}, 1 ligne account (hash BCrypt cout 12 verifiant le mot de passe), 0 runner, 0 race")
    void ca1_createsOneAccountAndNoRunnerInDatabase() throws Exception {
        // given : aucun compte nouveau-1
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'nouveau-1'")).isZero();

        // when
        String response = bodyOf(create("  Nouveau-1 ", PASSWORD, null).andExpect(status().isCreated()));

        // then : corps strict
        Map<String, Object> json = JsonPath.read(response, "$");
        assertThat(json).containsOnlyKeys("pseudo");
        assertThat(json).containsEntry("pseudo", "nouveau-1");
        assertThat(response).doesNotContain("password").doesNotContain("$2").doesNotContain("accountId");
        // then : base
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'nouveau-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM runner")).isZero();
        assertThat(count("SELECT COUNT(*) FROM race")).isZero();
        String hash = jdbc.queryForObject("SELECT password_hash FROM account WHERE pseudo = 'nouveau-1'", String.class);
        assertThat(hash).startsWith("$2a$12$").hasSize(60);
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, hash)).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("autre-mot-de-passe", hash)).isFalse();
    }

    @Test
    @Tag("INC7-CA3")
    @DisplayName("CA3 - Lievre existe : « Lievre », « LIEVRE », « lievre » et son propre mot de passe donnent 409 BUSINESS_CONFLICT, un seul compte, hash inchange")
    void ca3_existingPseudoIsRefusedWhateverTheCase() throws Exception {
        // given
        Long raceId = createSetupRace("IT7 conflit");
        trackRaceForCleanup(raceId);
        register(raceId, "Lievre");
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM account WHERE pseudo = 'lievre'", String.class);

        // when / then
        for (String attempt : List.of("Lievre", "LIEVRE", "lievre")) {
            create(attempt, PASSWORD, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
                .andExpect(jsonPath("$.detail").value(CONFLICT_DETAIL_LIEVRE));
        }
        create("Lievre", "motdepasse-1", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(header().doesNotExist("Authorization"));
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'lievre'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM runner")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM account WHERE pseudo = 'lievre'", String.class))
            .isEqualTo(hashBefore);
    }

    @Test
    @Tag("INC7-CA3")
    @DisplayName("CA3 - E26 : pseudo cree par E26 puis redemande a la casse pres : 409, toujours un seul compte et 0 coureur")
    void ca3_secondCreationOfAnE26AccountIsRefused() throws Exception {
        // given
        create("Nouveau-1", PASSWORD, null).andExpect(status().isCreated());

        // when / then
        create("NOUVEAU-1", PASSWORD, null).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'nouveau-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM runner")).isZero();
    }

    @Test
    @Tag("INC7-CA4")
    @DisplayName("CA4 - 10 creations concurrentes du meme pseudo : un seul 201, neuf 409, un seul compte en base, 0 coureur")
    void ca4_tenConcurrentCreationsGiveOneAccount() throws Exception {
        // given
        int callers = 10;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Integer> call = () -> {
                    ready.countDown();
                    go.await();
                    return create("Nouveau-1", PASSWORD, null).andReturn().getResponse().getStatus();
                };
                futures.add(executor.submit(call));
            }
            ready.await();

            // when
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            // then
            assertThat(statuses).hasSize(10);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(9);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'nouveau-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM runner")).isZero();
    }

    @Test
    @Tag("INC7-CA5")
    @DisplayName("CA5 (chaine reelle) - E26 : anonyme, SCANNER, ADMIN = 201")
    void ca5_anonymousScannerAndAdminAreAccepted() throws Exception {
        create("Anonyme-1", PASSWORD, null).andExpect(status().isCreated()).andExpect(jsonPath("$.pseudo").value("anonyme-1"));
        create("Scanner-1", PASSWORD, SCANNER).andExpect(status().isCreated()).andExpect(jsonPath("$.pseudo").value("scanner-1"));
        create("Admin-1", PASSWORD, ADMIN).andExpect(status().isCreated()).andExpect(jsonPath("$.pseudo").value("admin-1"));

        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM runner")).isZero();
    }

    @Test
    @Tag("INC7-CA5")
    @DisplayName("CA5 (chaine reelle) - E26 : compte coureur Lievre et identifiants faux (admin-test:mauvais, scanner, inconnu) = 401 « Identifiants invalides » sans WWW-Authenticate, aucun compte cree")
    void ca5_runnerAccountAndWrongCredentialsAreRefused() throws Exception {
        // given
        Long raceId = createSetupRace("IT7 matrice");
        trackRaceForCleanup(raceId);
        register(raceId, "Lievre");
        long accountsBefore = count("SELECT COUNT(*) FROM account");

        // when / then
        for (String authorization : List.of(basic("Lievre", "motdepasse-1"), basic("admin-test", "mauvais"),
            basic("scanner-test", "mauvais"), basic("inconnu", PASSWORD))) {
            MvcResult result = create("Refuse-1", PASSWORD, authorization).andExpect(status().isUnauthorized()).andReturn();
            assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
            String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("UNAUTHENTICATED");
            assertThat((String) JsonPath.read(json, "$.detail")).isEqualTo("Identifiants invalides");
        }
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(accountsBefore);
        assertThat(count("SELECT COUNT(*) FROM account WHERE pseudo = 'refuse-1'")).isZero();
    }

    @Test
    @Tag("INC7-CA6")
    @DisplayName("CA6 - compte cree par E26 : /api/account/me 200 pseudo nouveau-1 et liste vide ; /api/scan/me et /api/admin/races 401")
    void ca6_createdAccountIsRunnerOnly() throws Exception {
        // given
        create("Nouveau-1", PASSWORD, null).andExpect(status().isCreated());
        String credentials = basic("nouveau-1", PASSWORD);

        // when / then
        mvc.perform(get("/api/account/me").header("Authorization", credentials))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("nouveau-1"))
            .andExpect(jsonPath("$.registrations.length()").value(0));
        mvc.perform(get("/api/scan/me").header("Authorization", credentials))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/admin/races").header("Authorization", credentials))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @Tag("INC7-CA7")
    @DisplayName("CA7 - compte vide nouveau-1 : E20 sur course SETUP = 201 avec dossard et qrToken ; E3 avec nouveau-1 = 409 ; un seul coureur")
    void ca7_emptyAccountRegistersByE20ButNotByE3() throws Exception {
        // given
        Long raceId = createSetupRace("IT7 inscription compte vide");
        trackRaceForCleanup(raceId);
        create("Nouveau-1", PASSWORD, null).andExpect(status().isCreated());

        // when
        mvc.perform(post("/api/account/races/" + raceId + "/registrations")
                .header("Authorization", basic("nouveau-1", PASSWORD)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bib").value(1))
            .andExpect(jsonPath("$.qrToken").isNotEmpty());

        // then
        mvc.perform(post("/api/public/races/" + raceId + "/registrations").contentType(MediaType.APPLICATION_JSON)
                .content(body("nouveau-1", PASSWORD)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM runner")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM account")).isEqualTo(1);
        mvc.perform(get("/api/account/me").header("Authorization", basic("nouveau-1", PASSWORD)))
            .andExpect(jsonPath("$.registrations.length()").value(1));
    }

    /** Toutes les chaines qu'une ligne de journal peut porter : message, arguments, throwable, causes, trace. */
    private static List<String> everyTextOf(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(event.getFormattedMessage());
        texts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                texts.add(String.valueOf(argument));
            }
        }
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            texts.add(proxy.getClassName());
            texts.add(proxy.getMessage());
            if (proxy instanceof ThrowableProxy throwableProxy) {
                texts.add(String.valueOf(throwableProxy.getThrowable()));
            }
            for (var element : proxy.getStackTraceElementProxyArray()) {
                texts.add(element.getSTEAsString());
            }
        }
        return texts;
    }

    @Test
    @Tag("INC7-CA18")
    @DisplayName("CA18 - creation, conflit (409), validation (400) et refus (401) de E26 de bout en bout : aucune ligne de journal (application au niveau TRACE) ne contient le pseudo, le mot de passe ni un hash")
    void ca18_noPseudoPasswordNorHashInLogsEndToEnd() throws Exception {
        // given
        List<String> forbidden = new ArrayList<>(List.of("nouveau-1", "nouveau1", PASSWORD, "court12", "$2a$", "$2"));

        // when : creation, puis conflit, puis validations, puis refus d'acces
        create("  Nouveau-1 ", PASSWORD, null).andExpect(status().isCreated());
        String hash = jdbc.queryForObject("SELECT password_hash FROM account WHERE pseudo = 'nouveau-1'", String.class);
        forbidden.add(hash.toLowerCase(Locale.ROOT));
        create("NOUVEAU-1", PASSWORD, null).andExpect(status().isConflict());
        create("Nouveau-1", "court12", null).andExpect(status().isBadRequest());
        create("a b c", PASSWORD, null).andExpect(status().isBadRequest());
        create("Nouveau-2", PASSWORD, basic("nouveau-1", PASSWORD)).andExpect(status().isUnauthorized());

        // then
        List<ILoggingEvent> events = new ArrayList<>(logs.list);
        assertThat(events).as("le test capture bien des lignes (pas de passage a vide)").isNotEmpty();
        for (ILoggingEvent event : events) {
            for (String text : everyTextOf(event)) {
                if (text != null) {
                    String lower = text.toLowerCase(Locale.ROOT);
                    for (String secret : forbidden) {
                        assertThat(lower).as("ligne de journal [%s] %s", event.getLevel(), event.getFormattedMessage())
                            .doesNotContain(secret.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
    }
}
