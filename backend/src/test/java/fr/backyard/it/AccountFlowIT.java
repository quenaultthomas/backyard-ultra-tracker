package fr.backyard.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.domain.Account;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.AccountRepository;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 5 [IT] : chaine complete HTTP -> securite -> service -> JPA -> H2 (mode PostgreSQL) pour les comptes
 * coureurs. Verifie ce qui ne se voit qu'avec la vraie base et la vraie securite : schema V2, contrainte UNIQUE,
 * cout BCrypt 12, echappement des jokers LIKE, detachement avant suppression (FK RESTRICT), concurrence, journaux.
 * Les donnees creees sont supprimees apres chaque test.
 */
@Tag("INC-5")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFlowIT {

    private static final String ADMIN = basic("admin-test", "admin-secret");
    private static final String SCANNER = basic("scanner-test", "scanner-secret");
    private static final String ADMIN_TEST_HASH = "$2a$04$y5qZCIAHUZJzXkawr/klsep.f6zWCD7ErOpuAVwd.gwAalNLmDGZi";

    @Autowired
    MockMvc mvc;
    @Autowired
    RaceRepository raceRepository;
    @Autowired
    RunnerRepository runnerRepository;
    @Autowired
    PassageRepository passageRepository;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationContext context;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void captureLogs() {
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        rootLogger.detachAppender(logs);
        jdbc.update("DELETE FROM passage");
        jdbc.update("DELETE FROM runner");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM race");
    }

    private Long setupRace(String name, LocalDate date) {
        return raceRepository.save(new Race(name, date, 6706, 3600, 50)).getId();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private ResultActions registerNew(Long raceId, String pseudo, String password) throws Exception {
        return mvc.perform(post("/api/public/races/" + raceId + "/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pseudo\":\"" + pseudo + "\",\"password\":\"" + password + "\"}"));
    }

    private Account account(String storedPseudo) {
        return accountRepository.findByPseudo(storedPseudo).orElseThrow();
    }

    private String storedRunnerName(Long runnerId) {
        return jdbc.queryForObject("SELECT name FROM runner WHERE id = ?", String.class, runnerId);
    }

    @Test
    @DisplayName("CA1 - schema V2 : account(id, pseudo, password_hash) exactement, account_id et name nullables, UNIQUE sur pseudo")
    void ca1_schema() {
        List<String> accountColumns = jdbc.queryForList(
            "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'account'", String.class);
        assertThat(accountColumns).containsExactlyInAnyOrder("id", "pseudo", "password_hash");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE LOWER(TABLE_NAME) = 'runner' AND LOWER(COLUMN_NAME) = 'account_id'", String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE LOWER(TABLE_NAME) = 'runner' AND LOWER(COLUMN_NAME) = 'name'", String.class)).isEqualTo("YES");
        List<String> runnerColumns = jdbc.queryForList(
            "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'runner'", String.class);
        List<String> allColumns = new ArrayList<>(accountColumns);
        allColumns.addAll(runnerColumns);
        assertThat(allColumns).noneMatch(column -> column.matches(".*(ip|mail|phone|login|last).*"));

        jdbc.update("INSERT INTO account (pseudo, password_hash) VALUES ('lievre', 'h1')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO account (pseudo, password_hash) VALUES ('lievre', 'h2')"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CA4 / CA6 / CA30 - E3 « Lievre » : pseudo lievre, hash $2a$12$ de 60 caracteres, runner.name null, name lievre partout")
    void ca6_registrationStoresNormalizedPseudoAndCost12Hash() throws Exception {
        Long r1 = setupRace("IT5 Backyard Test", LocalDate.of(2026, 10, 3));

        registerNew(r1, "Lievre", "motdepasse-1")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pseudo").value("lievre"))
            .andExpect(jsonPath("$.name").value("lievre"))
            .andExpect(jsonPath("$.bib").value(1));

        Account lievre = account("lievre");
        assertThat(lievre.getPasswordHash()).startsWith("$2a$12$").hasSize(60);
        PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
        assertThat(encoder.matches("motdepasse-1", lievre.getPasswordHash())).isTrue();
        Runner runner = runnerRepository.findByAccountId(lievre.getId()).getFirst();
        assertThat(storedRunnerName(runner.getId())).isNull();
        perform(get("/api/public/races/" + r1 + "/board"), null)
            .andExpect(jsonPath("$.runners[0].name").value("lievre"));
        perform(get("/api/public/runners/" + runner.getId()), null)
            .andExpect(jsonPath("$.name").value("lievre"))
            .andExpect(jsonPath("$.accountId").doesNotExist())
            .andExpect(jsonPath("$.pseudo").doesNotExist());
        perform(put("/api/admin/runners/" + runner.getId()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"bib\":2,\"name\":\"Autre\"}"), ADMIN)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bib").value(2))
            .andExpect(jsonPath("$.name").value("lievre"))
            .andExpect(jsonPath("$.pseudo").value("lievre"));
        assertThat(storedRunnerName(runner.getId())).isNull();
    }

    @Test
    @DisplayName("CA4 - bean PasswordEncoder unique ; le hash staff de cout 4 reste verifiable, ADMIN 200")
    void ca4_staffHashOfCost4StillVerified() throws Exception {
        assertThat(context.getBeansOfType(PasswordEncoder.class)).hasSize(1);
        assertThat(context.getBean(PasswordEncoder.class).matches("admin-secret", ADMIN_TEST_HASH)).isTrue();

        perform(get("/api/admin/races"), ADMIN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("CA7 / CA12 - LIEVRE sur R2 refuse par le controle applicatif (BUSINESS_CONFLICT) ; connexion en LIEVRE acceptee")
    void ca7_duplicatePseudoAndCaseInsensitiveLogin() throws Exception {
        Long r1 = setupRace("IT5 R1", LocalDate.of(2026, 10, 3));
        Long r2 = setupRace("IT5 R2", LocalDate.of(2026, 11, 7));
        registerNew(r1, "Lievre", "motdepasse-1").andExpect(status().isCreated());

        registerNew(r2, "  LiEvRe ", "motdepasse-1")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(
                "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec."));

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(runnerRepository.findByRaceId(r2)).isEmpty();
        perform(get("/api/account/me"), basic("LIEVRE", "motdepasse-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("lievre"));
        perform(post("/api/account/races/" + r2 + "/registrations"), basic("Lievre", "motdepasse-1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bib").value(1));
        perform(get("/api/account/me"), basic("lievre", "motdepasse-1"))
            .andExpect(jsonPath("$.registrations.length()").value(2));
    }

    @Test
    @DisplayName("CA8 - deux creations simultanees de Tortue et TORTUE : une 201, une 409, un compte et un coureur")
    void ca8_concurrentCreations() throws Exception {
        Long r1 = setupRace("IT5 Concurrence 1", LocalDate.of(2026, 10, 3));
        Long r2 = setupRace("IT5 Concurrence 2", LocalDate.of(2026, 11, 7));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                () -> {
                    start.await();
                    return registerNew(r1, "Tortue", "motdepasse-1").andReturn().getResponse().getStatus();
                },
                () -> {
                    start.await();
                    return registerNew(r2, "TORTUE", "motdepasse-1").andReturn().getResponse().getStatus();
                });
            List<Future<Integer>> futures = new ArrayList<>();
            calls.forEach(call -> futures.add(executor.submit(call)));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(accountRepository.findAll()).extracting(Account::getPseudo).containsExactly("tortue");
        assertThat(runnerRepository.findByAccountId(account("tortue").getId())).hasSize(1);
    }

    @Test
    @DisplayName("CA17 / CA32 - E22 puis E23 : l'ancien mot de passe est refuse des la requete suivante ; hash de cout 12")
    void ca17_passwordResetAndChange() throws Exception {
        Long r1 = setupRace("IT5 Mots de passe", LocalDate.of(2026, 10, 3));
        registerNew(r1, "Lievre", "motdepasse-1").andExpect(status().isCreated());
        Long accountId = account("lievre").getId();

        perform(put("/api/admin/accounts/" + accountId + "/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"nouveau-mdp-42\"}"), ADMIN).andExpect(status().isNoContent());
        perform(get("/api/account/me"), basic("Lievre", "motdepasse-1")).andExpect(status().isUnauthorized());
        perform(get("/api/account/me"), basic("Lievre", "nouveau-mdp-42")).andExpect(status().isOk());
        assertThat(account("lievre").getPasswordHash()).startsWith("$2a$12$");

        perform(put("/api/account/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"nouveau-mdp-43\"}"), basic("Lievre", "nouveau-mdp-42"))
            .andExpect(status().isNoContent());
        perform(get("/api/account/me"), basic("Lievre", "nouveau-mdp-42"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Identifiants invalides"));
        perform(get("/api/account/me"), basic("Lievre", "nouveau-mdp-43")).andExpect(status().isOk());
        assertThat(account("lievre").getPasswordHash()).startsWith("$2a$12$");
    }

    @Test
    @DisplayName("CA33 / CA34 - suppression : coureurs detaches avec passages, scan 200, nom « Coureur n°1 », pseudo repris")
    void ca33_deleteAccountDetachesRunners() throws Exception {
        Long r1 = setupRace("IT5 Suppression R1", LocalDate.of(2026, 10, 3));
        Long r2 = setupRace("IT5 Suppression R2", LocalDate.of(2026, 11, 7));
        registerNew(r1, "Lievre", "motdepasse-1").andExpect(status().isCreated());
        perform(post("/api/account/races/" + r2 + "/registrations"), basic("Lievre", "motdepasse-1"))
            .andExpect(status().isCreated());
        Account lievre = account("lievre");
        Race race1 = raceRepository.findById(r1).orElseThrow();
        Instant startedAt = Instant.now().minusSeconds(2 * 3600 + 1800);
        race1.start(startedAt);
        raceRepository.save(race1);
        Runner inR1 = runnerRepository.findByRaceId(r1).getFirst();
        passageRepository.save(new Passage(inR1, 1, PassageSource.SCAN, startedAt.plusSeconds(3000)));
        passageRepository.save(new Passage(inR1, 2, PassageSource.SCAN, startedAt.plusSeconds(6600)));

        perform(delete("/api/admin/accounts/" + lievre.getId()), ADMIN).andExpect(status().isNoContent());

        assertThat(accountRepository.findById(lievre.getId())).isEmpty();
        List<Runner> detached = List.of(runnerRepository.findById(inR1.getId()).orElseThrow(),
            runnerRepository.findByRaceId(r2).getFirst());
        assertThat(detached).allSatisfy(runner -> {
            assertThat(runner.getAccount()).isNull();
            assertThat(runner.getBib()).isEqualTo(1);
            assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
            assertThat(storedRunnerName(runner.getId())).isNull();
        });
        assertThat(passageRepository.findByRunnerId(inR1.getId())).hasSize(2);
        perform(post("/api/scan/passages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"qrToken\":\"" + inR1.getQrToken() + "\",\"scannedAt\":\"" + Instant.now().minusSeconds(60)
                + "\"}"), SCANNER)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runnerName").value("Coureur n°1"));
        perform(get("/api/account/me"), basic("Lievre", "motdepasse-1")).andExpect(status().isUnauthorized());
        perform(get("/api/public/races/" + r1 + "/board"), null)
            .andExpect(jsonPath("$.runners[0].name").value("Coureur n°1"));
        perform(get("/api/admin/races/" + r1 + "/runners"), ADMIN)
            .andExpect(jsonPath("$[0].name").value("Coureur n°1"))
            .andExpect(jsonPath("$[0].pseudo").isEmpty())
            .andExpect(jsonPath("$[0].accountId").isEmpty());

        registerNew(r2, "Lievre", "motdepasse-9")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bib").value(2));
        assertThat(account("lievre").getId()).isNotEqualTo(lievre.getId());
        perform(get("/api/account/me"), basic("Lievre", "motdepasse-9"))
            .andExpect(jsonPath("$.registrations.length()").value(1));
        perform(get("/api/public/races/" + r1 + "/board"), null)
            .andExpect(jsonPath("$.runners[0].name").value("Coureur n°1"));
        perform(delete("/api/admin/accounts/" + lievre.getId()), ADMIN).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA43 - recherche litterale sur H2 : a_b seul, % vide, A_B normalise, puis a_b et a_b2")
    void ca43_literalSearch() throws Exception {
        Long r1 = setupRace("IT5 Recherche", LocalDate.of(2026, 10, 3));
        registerNew(r1, "a_b", "motdepasse-1").andExpect(status().isCreated());
        registerNew(r1, "axb", "motdepasse-1").andExpect(status().isCreated());

        perform(get("/api/admin/accounts").param("pseudo", "a_b"), ADMIN)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].pseudo").value("a_b"))
            .andExpect(jsonPath("$[0].runnerCount").value(1));
        perform(get("/api/admin/accounts").param("pseudo", "%"), ADMIN).andExpect(jsonPath("$.length()").value(0));
        perform(get("/api/admin/accounts").param("pseudo", "\\"), ADMIN).andExpect(jsonPath("$.length()").value(0));
        perform(get("/api/admin/accounts").param("pseudo", "A_B"), ADMIN)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].pseudo").value("a_b"));

        registerNew(r1, "A_B2", "motdepasse-1").andExpect(status().isCreated());
        perform(get("/api/admin/accounts").param("pseudo", "a_b"), ADMIN)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].pseudo").value("a_b"))
            .andExpect(jsonPath("$[1].pseudo").value("a_b2"));
        perform(get("/api/admin/accounts"), ADMIN)
            .andExpect(jsonPath("$[*].pseudo").value(org.hamcrest.Matchers.contains("a_b", "a_b2", "axb")));
    }

    @Test
    @DisplayName("CA19 - seules correspondances contenant password : PUT E22 et PUT E23 ; aucune reset, forgot, recover")
    void ca19_noAutomatedPasswordProcedure() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
            RequestMappingHandlerMapping.class);
        List<String> patterns = mapping.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream()
                .map(pattern -> info.getMethodsCondition().getMethods() + " " + pattern))
            .toList();

        assertThat(patterns).filteredOn(pattern -> pattern.contains("password"))
            .containsExactlyInAnyOrder("[PUT] /api/admin/accounts/{accountId}/password", "[PUT] /api/account/password");
        assertThat(patterns).noneMatch(pattern -> pattern.matches("(?i).*(reset|forgot|recover).*"));
    }

    @Test
    @DisplayName("CA20 - journaux d'un parcours complet : aucun mot de passe, hash ni Authorization ; lignes WARN/INFO sans pseudo")
    void ca20_logsWithoutSecretsNorPseudo() throws Exception {
        Long r1 = setupRace("IT5 Journaux", LocalDate.of(2026, 10, 3));
        registerNew(r1, "Lievre", "motdepasse-1").andExpect(status().isCreated());
        Long accountId = account("lievre").getId();
        perform(get("/api/account/me"), basic("Lievre", "motdepasse-2")).andExpect(status().isUnauthorized());
        perform(put("/api/account/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"nouveau-mdp-43\"}"), basic("Lievre", "motdepasse-1"))
            .andExpect(status().isNoContent());
        perform(put("/api/admin/accounts/" + accountId + "/password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"nouveau-mdp-42\"}"), ADMIN).andExpect(status().isNoContent());
        perform(delete("/api/admin/accounts/" + accountId), ADMIN).andExpect(status().isNoContent());

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(Objects::nonNull)
            .toList();
        assertThat(lines).allSatisfy(line -> assertThat(line)
            .doesNotContain("motdepasse-1").doesNotContain("motdepasse-2")
            .doesNotContain("nouveau-mdp-42").doesNotContain("nouveau-mdp-43")
            .doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$").doesNotContain("Authorization"));
        Map<String, String> expected = Map.of(
            "WARN", "Requête non authentifiée GET /api/account/me : Identifiants invalides",
            "INFO-1", "mot de passe modifié par le titulaire du compte " + accountId,
            "INFO-2", "mot de passe réinitialisé pour le compte " + accountId,
            "INFO-3", "compte " + accountId + " supprimé, 1 coureurs détachés");
        assertThat(lines).containsAll(expected.values());
        assertThat(expected.values())
            .allSatisfy(line -> assertThat(line.toLowerCase(Locale.ROOT)).doesNotContain("lievre"));
    }
}
