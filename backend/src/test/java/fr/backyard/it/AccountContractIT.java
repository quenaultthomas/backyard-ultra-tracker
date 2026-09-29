package fr.backyard.it;

import fr.backyard.domain.Account;
import fr.backyard.domain.Runner;
import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 5 [IT] (complement de {@code AccountFlowIT}) : contrat HTTP des comptes coureurs de bout en bout
 * (HTTP Basic reel, securite reelle, services reels, H2 en mode PostgreSQL). Nettoyage par {@link AbstractApiIT}
 * (deux phases : un compte inscrit a deux courses suivies, E20, est supprime apres tous ses coureurs).
 */
@Tag("INC-5")
class AccountContractIT extends AbstractApiIT {

    private static final String PASSWORD = "motdepasse-1";

    @Autowired
    JdbcTemplate jdbc;

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private Long race(String name) throws Exception {
        Long id = createSetupRace(name);
        trackRaceForCleanup(id);
        return id;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String authorization) throws Exception {
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private ResultActions registerNew(Long raceId, String pseudo, String password) throws Exception {
        return mvc.perform(post("/api/public/races/" + raceId + "/registrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pseudo\":\"" + pseudo + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions registerExisting(Long raceId, String pseudo) throws Exception {
        return perform(post("/api/account/races/" + raceId + "/registrations"), basic(pseudo, PASSWORD));
    }

    private ResultActions putPassword(String url, String newPassword, String authorization) throws Exception {
        return perform(put(url).contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"" + newPassword + "\"}"), authorization);
    }

    private Account account(String storedPseudo) {
        return accountRepository.findByPseudo(storedPseudo).orElseThrow();
    }

    private Long runnerIdIn(Long raceId, String pseudo) {
        Account account = account(pseudo);
        return runnerRepository.findByAccountId(account.getId()).stream()
            .filter(runner -> runner.getRace().getId().equals(raceId)).findFirst().orElseThrow().getId();
    }

    private String scanAndGetRunnerName(String qrToken, String scannedAtIso) throws Exception {
        MvcResult result = perform(post("/api/scan/passages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"qrToken\":\"" + qrToken + "\",\"scannedAt\":\"" + scannedAtIso + "\"}"), SCANNER)
            .andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.runnerName");
    }

    @Test
    @Tag("INC5-CA5")
    @DisplayName("CA5 - E3, E20, E21, E13, E14, E25 sans password, passwordHash, newPassword ni $2x$ ; E22, E23, E24 en 204 sans corps")
    void ca5_noSecretInAnyResponse() throws Exception {
        // given
        Long r1 = race("IT5C secrets 1");
        Long r2 = race("IT5C secrets 2");
        List<String> bodies = new java.util.ArrayList<>();
        bodies.add(registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString());
        // when
        bodies.add(registerExisting(r2, "Lievre").andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString());
        bodies.add(perform(get("/api/account/me"), basic("Lievre", PASSWORD)).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString());
        bodies.add(perform(get("/api/admin/races/" + r1 + "/runners"), ADMIN).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString());
        bodies.add(perform(get("/api/admin/runners/" + runnerIdIn(r1, "lievre")), ADMIN).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        bodies.add(perform(get("/api/admin/accounts"), ADMIN).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString());
        // then
        assertThat(bodies).hasSize(6).allSatisfy(body -> assertThat(body)
            .doesNotContain("password").doesNotContain("passwordHash").doesNotContain("newPassword")
            .doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$"));
        Long accountId = account("lievre").getId();
        assertThat(putPassword("/api/account/password", "nouveau-mdp-43", basic("Lievre", PASSWORD))
            .andExpect(status().isNoContent()).andReturn().getResponse().getContentAsString()).isEmpty();
        assertThat(putPassword("/api/admin/accounts/" + accountId + "/password", "nouveau-mdp-42", ADMIN)
            .andExpect(status().isNoContent()).andReturn().getResponse().getContentAsString()).isEmpty();
        assertThat(perform(delete("/api/admin/accounts/" + accountId), ADMIN).andExpect(status().isNoContent())
            .andReturn().getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @Tag("INC5-CA3")
    @DisplayName("CA3 - mot de passe : 8 et 72 octets acceptes ; 7 caracteres, 73 caracteres et 37 fois é (74 octets) refuses en 400 sur E3, E22 et E23")
    void ca3_passwordLengthsThroughRealChain() throws Exception {
        // given
        Long r1 = race("IT5C mdp");
        String seventyTwo = "a".repeat(72);
        // when / then : E3
        registerNew(r1, "court", "court12").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        registerNew(r1, "long73", "a".repeat(73)).andExpect(status().isBadRequest());
        registerNew(r1, "accents", "é".repeat(37)).andExpect(status().isBadRequest());
        assertThat(accountRepository.count()).isZero();
        registerNew(r1, "huit", "huitcar8").andExpect(status().isCreated());
        registerNew(r1, "soixantedouze", seventyTwo).andExpect(status().isCreated());
        // E22 et E23
        Long accountId = account("huit").getId();
        putPassword("/api/admin/accounts/" + accountId + "/password", "court12", ADMIN)
            .andExpect(status().isBadRequest());
        putPassword("/api/admin/accounts/" + accountId + "/password", "a".repeat(73), ADMIN)
            .andExpect(status().isBadRequest());
        putPassword("/api/admin/accounts/" + accountId + "/password", "é".repeat(37), ADMIN)
            .andExpect(status().isBadRequest());
        putPassword("/api/account/password", "court12", basic("huit", "huitcar8")).andExpect(status().isBadRequest());
        putPassword("/api/account/password", "é".repeat(37), basic("huit", "huitcar8"))
            .andExpect(status().isBadRequest());
        putPassword("/api/account/password", seventyTwo, basic("huit", "huitcar8")).andExpect(status().isNoContent());
        putPassword("/api/admin/accounts/" + accountId + "/password", "huitcar8", ADMIN)
            .andExpect(status().isNoContent());
        perform(get("/api/account/me"), basic("huit", "huitcar8")).andExpect(status().isOk());
    }

    @Test
    @Tag("INC5-CA9")
    @DisplayName("CA9 - E20 : 201 sur R2, deuxieme demande 409 « Déjà inscrit à cette course », course RUNNING 409, compte a 2 coureurs")
    void ca9_registerExistingAccountToAnotherRace() throws Exception {
        // given
        Long r1 = race("IT5C E20 R1");
        Long r2 = race("IT5C E20 R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        registerNew(r1, "Tortue", PASSWORD).andExpect(status().isCreated());
        // when / then : nominal
        registerExisting(r2, "Lievre").andExpect(status().isCreated())
            .andExpect(jsonPath("$.raceId").value(r2)).andExpect(jsonPath("$.bib").value(1))
            .andExpect(jsonPath("$.name").value("lievre"));
        assertThat(runnerRepository.findByAccountId(account("lievre").getId())).hasSize(2);
        // doublon
        registerExisting(r2, "Lievre").andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(jsonPath("$.detail").value("Déjà inscrit à cette course"));
        assertThat(runnerRepository.findByRaceId(r2)).hasSize(1);
        // course introuvable
        registerExisting(9_999_999L, "Lievre").andExpect(status().isNotFound());
        // inscriptions fermees
        startRace(r1, "2026-10-03T08:00:00Z");
        Long r3 = race("IT5C E20 R3");
        registerNew(r3, "Oublie", PASSWORD).andExpect(status().isCreated());
        registerExisting(r1, "Oublie").andExpect(status().isConflict());
        assertThat(runnerRepository.findByAccountId(account("oublie").getId())).hasSize(1);
    }

    @Test
    @Tag("INC5-CA10")
    @DisplayName("CA10 - titulaire de Lievre : E3 sur R2 refuse (409 « connectez-vous »), un seul compte, puis E20 : 201 lie au meme compte")
    void ca10_ownerCannotCreateSecondAccountButRegistersWithE20() throws Exception {
        // given
        Long r1 = race("IT5C titulaire R1");
        Long r2 = race("IT5C titulaire R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        // when : E3 avec ses propres identifiants
        registerNew(r2, "Lievre", PASSWORD).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(containsString("connectez-vous")));
        // then
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(runnerRepository.findByRaceId(r2)).isEmpty();
        registerExisting(r2, "Lievre").andExpect(status().isCreated()).andExpect(jsonPath("$.bib").value(1));
        assertThat(accountRepository.count()).isEqualTo(1);
        Long accountId = account("lievre").getId();
        assertThat(runnerRepository.findByRaceId(r2)).singleElement()
            .satisfies(runner -> assertThat(runner.accountId()).isEqualTo(accountId));
    }

    @Test
    @Tag("INC5-CA12")
    @DisplayName("CA12 - mot de passe faux, MAJUSCULES, pseudo inconnu : 401 UNAUTHENTICATED « Identifiants invalides », sans WWW-Authenticate ni Set-Cookie ; Lievre, lievre, LIEVRE : 200")
    void ca12_basicAuthenticationOutcomes() throws Exception {
        // given
        Long r1 = race("IT5C auth");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        // when / then : echecs
        for (String[] credentials : new String[][] {
            {"Lievre", "motdepasse-2"}, {"Lievre", "MOTDEPASSE-1"}, {"Inconnu", PASSWORD}}) {
            perform(get("/api/account/me"), basic(credentials[0], credentials[1]))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Identifiants invalides"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
        // succes
        for (String pseudo : List.of("Lievre", "lievre", "LIEVRE")) {
            perform(get("/api/account/me"), basic(pseudo, PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pseudo").value("lievre"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    @Tag("INC5-CA13")
    @DisplayName("CA13 - matrice d'acces reelle : RUNNER 401 hors /api/account, SCANNER et ADMIN 401 sur E21, SCANNER 403 sur admin, anonyme « Authentification requise »")
    void ca13_accessMatrix() throws Exception {
        // given
        Long r1 = race("IT5C matrice");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        String runner = basic("Lievre", PASSWORD);
        // E21
        perform(get("/api/account/me"), null).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Authentification requise"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        perform(get("/api/account/me"), runner).andExpect(status().isOk());
        perform(get("/api/account/me"), SCANNER).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Identifiants invalides"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        perform(get("/api/account/me"), ADMIN).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Identifiants invalides"));
        // RUNNER sur les autres prefixes
        for (String url : List.of("/api/scan/me", "/api/admin/races", "/api/public/races")) {
            perform(get(url), runner).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("Identifiants invalides"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        }
        perform(get("/api/public/races"), null).andExpect(status().isOk());
        // SCANNER et ADMIN
        perform(get("/api/scan/me"), SCANNER).andExpect(status().isOk());
        perform(get("/api/admin/races"), SCANNER).andExpect(status().isForbidden());
        perform(get("/api/public/races"), SCANNER).andExpect(status().isOk());
        perform(get("/api/admin/races"), ADMIN).andExpect(status().isOk());
    }

    @Test
    @Tag("INC5-CA14")
    @DisplayName("CA14 - E21 : inscriptions triees par raceDate croissante (R1 avant R2), qrToken du compte seulement")
    void ca14_myRegistrationsSortedByRaceDate() throws Exception {
        // given : R2 (2026-10-03 par createSetupRace) et une course plus ancienne creee directement
        Long later = race("IT5C tri tardive");
        Long earlier = raceRepository.save(new fr.backyard.domain.Race("IT5C tri precoce",
            java.time.LocalDate.of(2026, 9, 1), 6706, 3600, 50)).getId();
        trackRaceForCleanup(earlier);
        registerNew(later, "Lievre", PASSWORD).andExpect(status().isCreated());
        registerNew(later, "Tortue", PASSWORD).andExpect(status().isCreated());
        registerExisting(earlier, "Lievre").andExpect(status().isCreated());
        // when / then
        String laterToken = runnerRepository.findById(runnerIdIn(later, "lievre")).orElseThrow().getQrToken();
        String earlierToken = runnerRepository.findById(runnerIdIn(earlier, "lievre")).orElseThrow().getQrToken();
        String tortueToken = runnerRepository.findById(runnerIdIn(later, "tortue")).orElseThrow().getQrToken();
        perform(get("/api/account/me"), basic("Lievre", PASSWORD)).andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("lievre"))
            .andExpect(jsonPath("$.registrations.length()").value(2))
            .andExpect(jsonPath("$.registrations[*].raceId").value(contains(earlier.intValue(), later.intValue())))
            .andExpect(jsonPath("$.registrations[*].qrToken").value(contains(earlierToken, laterToken)))
            .andExpect(jsonPath("$.registrations[*].qrToken").value(not(org.hamcrest.Matchers.hasItem(tortueToken))));
    }

    @Test
    @Tag("INC5-CA15")
    @DisplayName("CA15 - E13 : coureur lie (accountId, pseudo, name = lievre) et coureur sans compte (accountId null, pseudo null, « Coureur n°{bib} »)")
    void ca15_adminSeesAccountOfRunner() throws Exception {
        // given
        Long r1 = race("IT5C admin");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        Long accountId = account("lievre").getId();
        Runner legacy = runnerRepository.save(new Runner(raceRepository.findById(r1).orElseThrow(), 7, "tok-legacy"));
        // when / then
        perform(get("/api/admin/races/" + r1 + "/runners"), ADMIN).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accountId").value(accountId))
            .andExpect(jsonPath("$[0].pseudo").value("lievre"))
            .andExpect(jsonPath("$[0].name").value("lievre"))
            .andExpect(jsonPath("$[1].id").value(legacy.getId()))
            .andExpect(jsonPath("$[1].accountId").isEmpty())
            .andExpect(jsonPath("$[1].pseudo").isEmpty())
            .andExpect(jsonPath("$[1].name").value("Coureur n°7"));
    }

    @Test
    @Tag("INC5-CA16")
    @DisplayName("CA16 - E4 et E5 d'un compte inscrit a deux courses : aucun accountId, account ni pseudo, runnerId distincts, aucun qrToken")
    void ca16_noPublicTechnicalCorrelation() throws Exception {
        // given
        Long r1 = race("IT5C public R1");
        Long r2 = race("IT5C public R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        registerExisting(r2, "Lievre").andExpect(status().isCreated());
        Long runner1 = runnerIdIn(r1, "lievre");
        Long runner2 = runnerIdIn(r2, "lievre");
        // when
        List<String> bodies = List.of(
            perform(get("/api/public/races/" + r1 + "/board"), null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(),
            perform(get("/api/public/races/" + r2 + "/board"), null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(),
            perform(get("/api/public/runners/" + runner1), null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(),
            perform(get("/api/public/runners/" + runner2), null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        // then
        assertThat(runner1).isNotEqualTo(runner2);
        assertThat(bodies).allSatisfy(body -> assertThat(body)
            .doesNotContain("accountId").doesNotContain("\"account\"").doesNotContain("\"pseudo\"")
            .doesNotContain("qrToken").contains("\"name\":\"lievre\""));
    }

    @Test
    @Tag("INC5-CA18")
    @DisplayName("CA18 - E22 : compte 99 inexistant 404, mot de passe court 400, anonyme 401, SCANNER 403, RUNNER 401")
    void ca18_resetPasswordErrors() throws Exception {
        // given
        Long r1 = race("IT5C reset erreurs");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        Long accountId = account("lievre").getId();
        String url = "/api/admin/accounts/" + accountId + "/password";
        // when / then
        putPassword("/api/admin/accounts/999999/password", "nouveau-mdp-42", ADMIN).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        putPassword(url, "court12", ADMIN).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        putPassword(url, "nouveau-mdp-42", null).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Authentification requise"));
        putPassword(url, "nouveau-mdp-42", SCANNER).andExpect(status().isForbidden());
        putPassword(url, "nouveau-mdp-42", basic("Lievre", PASSWORD)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Identifiants invalides"));
        perform(get("/api/account/me"), basic("Lievre", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @Tag("INC5-CA22")
    @DisplayName("CA22 - pseudos admin-test et scanner-test : aucun droit staff, le compte technique n'a aucun acces coureur, aucun ne masque l'autre")
    void ca22_pseudoEqualToTechnicalAccount() throws Exception {
        // given
        Long r1 = race("IT5C homonymes");
        registerNew(r1, "admin-test", PASSWORD).andExpect(status().isCreated());
        registerNew(r1, "scanner-test", "scanner-secret").andExpect(status().isCreated());
        String pseudoAdmin = basic("admin-test", PASSWORD);
        String pseudoScanner = basic("scanner-test", "scanner-secret");
        // when / then
        perform(get("/api/admin/races"), pseudoAdmin).andExpect(status().isUnauthorized());
        perform(get("/api/scan/me"), pseudoAdmin).andExpect(status().isUnauthorized());
        perform(get("/api/account/me"), pseudoAdmin).andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("admin-test"));
        perform(get("/api/admin/races"), ADMIN).andExpect(status().isOk());
        perform(get("/api/account/me"), ADMIN).andExpect(status().isUnauthorized());
        perform(get("/api/account/me"), pseudoScanner).andExpect(status().isOk())
            .andExpect(jsonPath("$.pseudo").value("scanner-test"));
        perform(get("/api/scan/me"), pseudoScanner).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("SCANNER"));
        perform(get("/api/admin/races"), pseudoScanner).andExpect(status().isForbidden());
    }

    @Test
    @Tag("INC5-CA23")
    @DisplayName("CA23 - coureur supprime en SETUP (E16) : le compte subsiste, E21 liste vide, inscription a R2 en 201")
    void ca23_accountSurvivesRunnerDeletion() throws Exception {
        // given
        Long r1 = race("IT5C suppression coureur R1");
        Long r2 = race("IT5C suppression coureur R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        Long accountId = account("lievre").getId();
        // when
        perform(delete("/api/admin/runners/" + runnerIdIn(r1, "lievre")), ADMIN).andExpect(status().isNoContent());
        // then
        assertThat(accountRepository.findById(accountId)).isPresent();
        perform(get("/api/account/me"), basic("Lievre", PASSWORD)).andExpect(status().isOk())
            .andExpect(jsonPath("$.registrations.length()").value(0));
        registerExisting(r2, "Lievre").andExpect(status().isCreated());
    }

    @Test
    @Tag("INC5-CA24")
    @DisplayName("CA24 - deux courses RUNNING : ACTIVE en R1 et DNF en R2, chacun avec le statut de sa course")
    void ca24_runnerStatusPerRace() throws Exception {
        // given
        Long r1 = race("IT5C parallele R1");
        Long r2 = race("IT5C parallele R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        registerExisting(r2, "Lievre").andExpect(status().isCreated());
        startRace(r1, "2026-10-03T08:00:00Z");
        startRace(r2, "2026-10-03T08:00:00Z");
        // when
        perform(post("/api/admin/runners/" + runnerIdIn(r2, "lievre") + "/dnf")
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"VOLUNTARY\"}"), ADMIN)
            .andExpect(status().isOk());
        // then
        perform(get("/api/account/me"), basic("Lievre", PASSWORD)).andExpect(status().isOk())
            .andExpect(jsonPath("$.registrations[?(@.raceId==" + r1 + ")].status").value(contains("ACTIVE")))
            .andExpect(jsonPath("$.registrations[?(@.raceId==" + r1 + ")].raceStatus").value(contains("RUNNING")))
            .andExpect(jsonPath("$.registrations[?(@.raceId==" + r2 + ")].status").value(contains("DNF")))
            .andExpect(jsonPath("$.registrations[?(@.raceId==" + r2 + ")].raceStatus").value(contains("RUNNING")));
    }

    @Test
    @Tag("INC5-CA29")
    @DisplayName("CA29 - Lievre puis Lievre2 sur R1 : deux 201, deux comptes distincts, dossards 1 et 2")
    void ca29_twoDifferentPseudosInSameRace() throws Exception {
        // given
        Long r1 = race("IT5C deux comptes");
        // when
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated()).andExpect(jsonPath("$.bib").value(1));
        registerNew(r1, "Lievre2", PASSWORD).andExpect(status().isCreated()).andExpect(jsonPath("$.bib").value(2));
        // then
        assertThat(accountRepository.findAll()).extracting(Account::getPseudo)
            .containsExactlyInAnyOrder("lievre", "lievre2");
        assertThat(runnerRepository.findByRaceId(r1)).hasSize(2);
    }

    @Test
    @Tag("INC5-CA32")
    @DisplayName("CA32 - E23 : mot de passe court 400 et hash inchange ; anonyme 401, SCANNER 401, ADMIN 401")
    void ca32_changePasswordErrorsAndRoles() throws Exception {
        // given
        Long r1 = race("IT5C changement");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        String hashBefore = account("lievre").getPasswordHash();
        // when / then
        putPassword("/api/account/password", "court12", basic("Lievre", PASSWORD)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(account("lievre").getPasswordHash()).isEqualTo(hashBefore);
        putPassword("/api/account/password", "nouveau-mdp-43", null).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Authentification requise"));
        putPassword("/api/account/password", "nouveau-mdp-43", SCANNER).andExpect(status().isUnauthorized());
        putPassword("/api/account/password", "nouveau-mdp-43", ADMIN).andExpect(status().isUnauthorized());
        assertThat(account("lievre").getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    @Tag("INC5-CA33")
    @DisplayName("CA33 - E24 : anonyme 401, SCANNER 403, RUNNER 401 sans effet ; compte inconnu 404")
    void ca33_deleteAccountAccessRules() throws Exception {
        // given
        Long r1 = race("IT5C suppression droits");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        Long accountId = account("lievre").getId();
        String url = "/api/admin/accounts/" + accountId;
        // when / then
        perform(delete(url), null).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Authentification requise"));
        perform(delete(url), SCANNER).andExpect(status().isForbidden());
        perform(delete(url), basic("Lievre", PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(accountRepository.findById(accountId)).isPresent();
        perform(delete("/api/admin/accounts/999999"), ADMIN).andExpect(status().isNotFound());
    }

    @Test
    @Tag("INC5-CA34")
    @DisplayName("CA34 - coureur detache de R2 (SETUP) : E15 {bib:5} donne « Coureur n°5 » en reponse et en E13")
    void ca34_detachedRunnerDisplayNameFollowsBib() throws Exception {
        // given
        Long r2 = race("IT5C detache");
        registerNew(r2, "Lievre", PASSWORD).andExpect(status().isCreated());
        Long runnerId = runnerIdIn(r2, "lievre");
        perform(delete("/api/admin/accounts/" + account("lievre").getId()), ADMIN).andExpect(status().isNoContent());
        // when / then
        perform(put("/api/admin/runners/" + runnerId).contentType(MediaType.APPLICATION_JSON).content("{\"bib\":5}"),
            ADMIN).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coureur n°5"))
            .andExpect(jsonPath("$.pseudo").isEmpty());
        perform(get("/api/admin/races/" + r2 + "/runners"), ADMIN)
            .andExpect(jsonPath("$[0].name").value("Coureur n°5"));
        assertThat(jdbc.queryForObject("SELECT name FROM runner WHERE id = ?", String.class, runnerId)).isNull();
    }

    @Test
    @Tag("INC5-CA42")
    @DisplayName("CA42 - E25 : liste triee avec runnerCount, filtre normalise (LIE, %20tor%20, vide, zzz) ; seuls accountId, pseudo, runnerCount")
    void ca42_listAndSearchAccounts() throws Exception {
        // given : lievre (2 coureurs), oublie (0 coureur, coureur supprime), tortue (1 coureur)
        Long r1 = race("IT5C liste R1");
        Long r2 = race("IT5C liste R2");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        registerExisting(r2, "Lievre").andExpect(status().isCreated());
        registerNew(r1, "Tortue", PASSWORD).andExpect(status().isCreated());
        registerNew(r1, "Oublie", PASSWORD).andExpect(status().isCreated());
        Long oublieId = account("oublie").getId();
        perform(delete("/api/admin/runners/" + runnerIdIn(r1, "oublie")), ADMIN).andExpect(status().isNoContent());
        try {
            // when / then
            perform(get("/api/admin/accounts"), ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].pseudo").value(contains("lievre", "oublie", "tortue")))
                .andExpect(jsonPath("$[*].runnerCount").value(contains(2, 0, 1)))
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].accountId").exists())
                .andExpect(jsonPath("$[0].pseudo").exists())
                .andExpect(jsonPath("$[0].runnerCount").exists());
            perform(get("/api/admin/accounts").param("pseudo", "LIE"), ADMIN)
                .andExpect(jsonPath("$[*].pseudo").value(contains("lievre", "oublie")));
            perform(get("/api/admin/accounts").param("pseudo", " tor "), ADMIN)
                .andExpect(jsonPath("$[*].pseudo").value(contains("tortue")));
            perform(get("/api/admin/accounts").param("pseudo", ""), ADMIN)
                .andExpect(jsonPath("$.length()").value(3));
            perform(get("/api/admin/accounts").param("pseudo", "zzz"), ADMIN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            perform(get("/api/admin/accounts"), null).andExpect(status().isUnauthorized());
            perform(get("/api/admin/accounts"), SCANNER).andExpect(status().isForbidden());
            perform(get("/api/admin/accounts"), basic("Lievre", PASSWORD)).andExpect(status().isUnauthorized());
        } finally {
            // limite documentee d'AbstractApiIT : un compte sans coureur n'est pas collecte
            jdbc.update("DELETE FROM account WHERE id = ?", oublieId);
        }
    }

    @Test
    @Tag("INC5-CA6")
    @Tag("INC5-CA30")
    @DisplayName("Point d'interpretation 2 (RG18) - scan E6 d'un coureur lie : runnerName = pseudo ; sans compte : « Coureur n°{bib} » ; E4 identique")
    void rg18_scanResponseAndBoardCarryDisplayName() throws Exception {
        // given
        Long r1 = race("IT5C scan nom");
        registerNew(r1, "Lievre", PASSWORD).andExpect(status().isCreated());
        Runner legacy = runnerRepository.save(new Runner(raceRepository.findById(r1).orElseThrow(), 9, "tok-legacy"));
        startRace(r1, "2026-10-03T08:00:00Z");
        clock.set(java.time.Instant.parse("2026-10-03T08:35:00Z"));
        String linkedToken = runnerRepository.findById(runnerIdIn(r1, "lievre")).orElseThrow().getQrToken();
        // when
        String linkedName = scanAndGetRunnerName(linkedToken, "2026-10-03T08:30:00Z");
        String legacyName = scanAndGetRunnerName(legacy.getQrToken(), "2026-10-03T08:31:00Z");
        // then
        assertThat(linkedName).isEqualTo("lievre");
        assertThat(legacyName).isEqualTo("Coureur n°9");
        perform(get("/api/public/races/" + r1 + "/board"), null).andExpect(status().isOk())
            .andExpect(jsonPath("$.runners[?(@.bib==1)].name").value(contains("lievre")))
            .andExpect(jsonPath("$.runners[?(@.bib==9)].name").value(contains("Coureur n°9")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runner WHERE race_id = ? AND name IS NOT NULL",
            Long.class, r1)).isZero();
    }

    @Test
    @Tag("INC5-CA31")
    @Tag("INC5-CA21")
    @DisplayName("CA31 / CA21 - coureur historique (nom brut en base, sans compte) : E4, E5, E13 « Coureur n°1 », jamais « Alice » ; scan 200, DNF manuel et E4 comme avant l'incrément")
    void ca31_legacyRunnerKeepsRaceBehaviourUnderNeutralName() throws Exception {
        // given : coureur cree comme avant l'incrément (colonne name renseignee en SQL, aucun compte)
        Long r1 = race("IT5C historique");
        jdbc.update("INSERT INTO runner (race_id, bib, name, qr_token, status) VALUES (?, 1, 'Alice', 'tok-alice', "
            + "'ACTIVE')", r1);
        Long aliceId = jdbc.queryForObject("SELECT id FROM runner WHERE qr_token = 'tok-alice'", Long.class);
        startRace(r1, "2026-10-03T08:00:00Z");
        clock.set(java.time.Instant.parse("2026-10-03T08:30:00Z"));
        // when
        String scanName = scanAndGetRunnerName("tok-alice", "2026-10-03T08:30:00Z");
        // then : nom neutre partout, aucune fuite du nom brut
        assertThat(scanName).isEqualTo("Coureur n°1");
        List<String> bodies = List.of(
            perform(get("/api/public/races/" + r1 + "/board"), null)
                .andExpect(jsonPath("$.runners[0].name").value("Coureur n°1"))
                .andExpect(jsonPath("$.runners[0].completedLoops").value(1)).andReturn().getResponse()
                .getContentAsString(),
            perform(get("/api/public/runners/" + aliceId), null)
                .andExpect(jsonPath("$.name").value("Coureur n°1")).andReturn().getResponse().getContentAsString(),
            perform(get("/api/admin/races/" + r1 + "/runners"), ADMIN)
                .andExpect(jsonPath("$[0].name").value("Coureur n°1"))
                .andExpect(jsonPath("$[0].pseudo").isEmpty()).andReturn().getResponse().getContentAsString());
        assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain("Alice"));
        // DNF manuel comme avant l'incrément
        perform(post("/api/admin/runners/" + aliceId + "/dnf").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"VOLUNTARY\"}"), ADMIN).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DNF")).andExpect(jsonPath("$.name").value("Coureur n°1"));
        perform(get("/api/public/races/" + r1 + "/board"), null)
            .andExpect(jsonPath("$.runners[0].status").value("DNF"))
            .andExpect(jsonPath("$.runners[0].name").value("Coureur n°1"));
    }
}
