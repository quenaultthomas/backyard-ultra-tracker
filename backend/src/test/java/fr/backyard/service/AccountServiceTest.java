package fr.backyard.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.domain.Account;
import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static fr.backyard.testsupport.AccountFakes.account;
import static fr.backyard.testsupport.AccountFakes.race;
import static fr.backyard.testsupport.AccountFakes.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Spec increment 5 - AccountService [unit] : creation (RG5, RG7), authentification (RG9), mes inscriptions (RG11),
 * changement et reinitialisation du mot de passe (RG14, RG19), suppression (RG20), recherche (RG24), journaux (RG16).
 * Repositories doubles en memoire, vrai BCrypt de cout 4 (rapide) : aucune base, aucun contexte Spring.
 */
@Tag("INC-5")
class AccountServiceTest {

    private static final String PASSWORD = "motdepasse-1";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final FakeRepositories fakes = new FakeRepositories();
    private final AccountFakes accountFakes = new AccountFakes(fakes);
    private final AccountService service =
        new AccountService(accountFakes.accountRepository, fakes.runnerRepository, passwordEncoder);

    private final Race r1 = race(1L, "Backyard Test", LocalDate.of(2026, 10, 3), RaceStatus.SETUP);
    private final Race r2 = race(2L, "Backyard Automne", LocalDate.of(2026, 11, 7), RaceStatus.SETUP);

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AccountService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLogger.addAppender(logs);
        fakes.withRaces(r1, r2);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
    }

    private List<String> logLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private Account existingLievre() {
        Account lievre = account(5L, "lievre", passwordEncoder.encode(PASSWORD));
        accountFakes.withAccounts(lievre);
        return lievre;
    }

    // ----- creation (RG2, RG3, RG5, RG7) -----

    @Test
    @DisplayName("CA6 - creation de « Lievre » : compte de pseudo lievre, hash BCrypt du mot de passe, jamais le mot de passe")
    void ca6_createStoresNormalizedPseudoAndHash() {
        Account created = service.create("Lievre", PASSWORD);

        assertThat(accountFakes.accounts()).containsExactly(created);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPseudo()).isEqualTo("lievre");
        assertThat(created.getPasswordHash()).startsWith("$2a$").hasSize(60).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
    }

    @ParameterizedTest(name = "CA7 - « {0} » alors que lievre existe : 409, detail avec lievre normalise, aucun compte cree")
    @ValueSource(strings = {"lievre", "Lievre", "LIEVRE", "  LiEvRe "})
    void ca7_pseudoAlreadyTakenWhateverTheCase(String input) {
        existingLievre();

        assertThatThrownBy(() -> service.create(input, "autre-mdp-99"))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessage("Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec.")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("LIEVRE").doesNotContain("LiEvRe"));

        assertThat(accountFakes.accounts()).extracting(Account::getPseudo).containsExactly("lievre");
        assertThat(accountFakes.savedAccounts()).isEmpty();
    }

    @Test
    @DisplayName("CA10 / CL13 - titulaire qui repasse par la creation avec son bon mot de passe : 409, aucun compte cree")
    void ca10_ownerWithRightPasswordIsRefused() {
        existingLievre();

        assertThatThrownBy(() -> service.create("Lievre", PASSWORD))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining("connectez-vous");

        assertThat(accountFakes.accounts()).hasSize(1);
    }

    @Test
    @DisplayName("CA29 - deux pseudos differents (Lievre, Lievre2) : deux comptes distincts, aucun controle d'identite")
    void ca29_twoDifferentPseudos() {
        service.create("Lievre", PASSWORD);
        service.create("Lievre2", PASSWORD);

        assertThat(accountFakes.accounts()).extracting(Account::getPseudo).containsExactly("lievre", "lievre2");
    }

    @ParameterizedTest(name = "RG2 - pseudo invalide ''{0}'' : InvalidInputException, aucun compte")
    @ValueSource(strings = {"ab", "Jean Dupont", "élan", ""})
    void rg2_invalidPseudoIsRejectedByTheService(String input) {
        assertThatThrownBy(() -> service.create(input, PASSWORD))
            .isInstanceOf(InvalidInputException.class)
            .hasMessageStartingWith("Pseudo invalide");

        verify(accountFakes.accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("RG3 - mot de passe invalide a la creation : InvalidInputException, aucun compte")
    void rg3_invalidPasswordIsRejectedByTheService() {
        assertThatThrownBy(() -> service.create("Lievre", "court12"))
            .isInstanceOf(InvalidInputException.class)
            .hasMessageStartingWith("Mot de passe invalide");

        verify(accountFakes.accountRepository, never()).save(any());
    }

    // ----- authentification (RG9) -----

    @ParameterizedTest(name = "CA12 - pseudo presente ''{0}'' : le compte lievre")
    @ValueSource(strings = {"Lievre", "lievre", "LIEVRE", " lievre "})
    void ca12_credentialsFoundWhateverTheCase(String presented) {
        Account lievre = existingLievre();

        AccountCredentials credentials = service.findCredentials(presented).orElseThrow();

        assertThat(credentials.accountId()).isEqualTo(5L);
        assertThat(credentials.pseudo()).isEqualTo("lievre");
        assertThat(credentials.passwordHash()).isEqualTo(lievre.getPasswordHash());
        assertThat(credentials.toString()).doesNotContain("$2a$").doesNotContain("lievre");
    }

    @Test
    @DisplayName("CA12 - pseudo inconnu : aucun compte")
    void ca12_unknownPseudo() {
        existingLievre();

        assertThat(service.findCredentials("Inconnu")).isEmpty();
    }

    // ----- mes inscriptions (RG11) -----

    @Test
    @DisplayName("CA14 - inscrit en R2 (bib 1) puis R1 (bib 3) : R1 puis R2, avec les qr_token ; rien de Tortue")
    void ca14_registrationsSortedByRaceDate() {
        Account lievre = existingLievre();
        Account tortue = account(6L, "tortue", "$2a$04$x");
        accountFakes.withAccounts(tortue);
        fakes.withRunners(
            runner(20L, r2, 1, lievre, "tok-a-r2"),
            runner(21L, r1, 3, lievre, "tok-a-r1"),
            runner(22L, r1, 4, tortue, "tok-b-r1"));

        AccountRegistrationsView view = service.registrations(5L);

        assertThat(view.pseudo()).isEqualTo("lievre");
        assertThat(view.registrations())
            .extracting(AccountRegistrationView::raceId, AccountRegistrationView::raceName,
                AccountRegistrationView::raceDate, AccountRegistrationView::raceStatus,
                AccountRegistrationView::runnerId, AccountRegistrationView::bib, AccountRegistrationView::status,
                AccountRegistrationView::qrToken)
            .containsExactly(
                tuple(1L, "Backyard Test", LocalDate.of(2026, 10, 3), RaceStatus.SETUP, 21L, 3, RunnerStatus.ACTIVE,
                    "tok-a-r1"),
                tuple(2L, "Backyard Automne", LocalDate.of(2026, 11, 7), RaceStatus.SETUP, 20L, 1,
                    RunnerStatus.ACTIVE, "tok-a-r2"));
    }

    @Test
    @DisplayName("RG11 - meme date de course : tri par raceId croissant")
    void rg11_sameDateSortedByRaceId() {
        Account lievre = existingLievre();
        Race r9 = race(9L, "Backyard Bis", LocalDate.of(2026, 10, 3), RaceStatus.SETUP);
        fakes.withRaces(r9).withRunners(runner(30L, r9, 1, lievre, "tok-r9"), runner(31L, r1, 1, lievre, "tok-r1"));

        assertThat(service.registrations(5L).registrations())
            .extracting(AccountRegistrationView::raceId)
            .containsExactly(1L, 9L);
    }

    @Test
    @DisplayName("CA24 - courses en parallele : chaque coureur avec le statut de sa course")
    void ca24_eachRunnerWithItsOwnStatus() {
        Account lievre = existingLievre();
        r1.setStatus(RaceStatus.RUNNING);
        r2.setStatus(RaceStatus.RUNNING);
        Runner inR2 = runner(41L, r2, 1, lievre, "tok-r2");
        inR2.markDnf(DnfReason.TIMEOUT, 2);
        fakes.withRunners(runner(40L, r1, 1, lievre, "tok-r1"), inR2);

        assertThat(service.registrations(5L).registrations())
            .extracting(AccountRegistrationView::raceId, AccountRegistrationView::raceStatus,
                AccountRegistrationView::status)
            .containsExactly(
                tuple(1L, RaceStatus.RUNNING, RunnerStatus.ACTIVE),
                tuple(2L, RaceStatus.RUNNING, RunnerStatus.DNF));
    }

    @Test
    @DisplayName("CL21 - compte sans inscription : liste vide")
    void cl21_noRegistration() {
        existingLievre();

        assertThat(service.registrations(5L).registrations()).isEmpty();
    }

    @Test
    @DisplayName("RG11 - compte disparu (supprime entre-temps) : ResourceNotFoundException")
    void rg11_unknownAccount() {
        assertThatThrownBy(() -> service.registrations(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Compte introuvable : id 99");
    }

    // ----- mot de passe (RG14, RG19) -----

    @Test
    @DisplayName("CA32 - changement par le titulaire : nouveau hash, ancien refuse ; journal sans pseudo ni secret")
    void ca32_changePassword() {
        Account lievre = existingLievre();
        String oldHash = lievre.getPasswordHash();

        service.changePassword(5L, "nouveau-mdp-43");

        assertThat(lievre.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches("nouveau-mdp-43", lievre.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, lievre.getPasswordHash())).isFalse();
        assertThat(accountFakes.savedAccounts()).containsExactly(lievre);
        assertThat(logLines()).containsExactly("mot de passe modifié par le titulaire du compte 5");
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertNoSecretNorPseudoInLogs("nouveau-mdp-43");
    }

    @Test
    @DisplayName("CA32 - nouveau mot de passe trop court : InvalidInputException, hash inchange, aucun journal")
    void ca32_invalidNewPassword() {
        Account lievre = existingLievre();
        String oldHash = lievre.getPasswordHash();

        assertThatThrownBy(() -> service.changePassword(5L, "court12")).isInstanceOf(InvalidInputException.class);

        assertThat(lievre.getPasswordHash()).isEqualTo(oldHash);
        assertThat(logLines()).isEmpty();
    }

    @Test
    @DisplayName("CA17 - reinitialisation par l'admin : nouveau hash valable sans autre condition ; journal attendu")
    void ca17_resetPassword() {
        Account lievre = existingLievre();

        service.resetPassword(5L, "nouveau-mdp-42");

        assertThat(passwordEncoder.matches("nouveau-mdp-42", lievre.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, lievre.getPasswordHash())).isFalse();
        assertThat(logLines()).containsExactly("mot de passe réinitialisé pour le compte 5");
        assertNoSecretNorPseudoInLogs("nouveau-mdp-42");
    }

    @Test
    @DisplayName("CA18 - reinitialisation d'un compte inexistant : ResourceNotFoundException, aucun journal INFO")
    void ca18_resetUnknownAccount() {
        assertThatThrownBy(() -> service.resetPassword(99L, "nouveau-mdp-42"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Compte introuvable : id 99");

        assertThat(logLines()).isEmpty();
    }

    @Test
    @DisplayName("CA18 - reinitialisation avec court12 : InvalidInputException, hash inchange")
    void ca18_resetWithInvalidPassword() {
        Account lievre = existingLievre();
        String oldHash = lievre.getPasswordHash();

        assertThatThrownBy(() -> service.resetPassword(5L, "court12")).isInstanceOf(InvalidInputException.class);

        assertThat(lievre.getPasswordHash()).isEqualTo(oldHash);
    }

    // ----- suppression (RG20) -----

    @Test
    @DisplayName("CA33 - suppression : coureurs detaches (dossard, statut, token conserves), compte supprime, journal")
    void ca33_deleteDetachesRunnersThenDeletesAccount() {
        Account lievre = existingLievre();
        r1.setStatus(RaceStatus.RUNNING);
        Runner inR1 = runner(50L, r1, 1, lievre, "tok-r1");
        Runner inR2 = runner(51L, r2, 1, lievre, "tok-r2");
        Runner other = runner(52L, r1, 2, null, "tok-other");
        fakes.withRunners(inR1, inR2, other);

        service.delete(5L);

        assertThat(accountFakes.deletedAccounts()).containsExactly(lievre);
        assertThat(accountFakes.accounts()).isEmpty();
        assertThat(List.of(inR1, inR2)).allSatisfy(runner -> assertThat(runner.getAccount()).isNull());
        assertThat(inR1.getBib()).isEqualTo(1);
        assertThat(inR1.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(inR1.getQrToken()).isEqualTo("tok-r1");
        assertThat(inR1.displayName()).isEqualTo("Coureur n°1");
        assertThat(fakes.runners()).containsExactlyInAnyOrder(inR1, inR2, other);
        assertThat(fakes.savedRunners()).containsExactlyInAnyOrder(inR1, inR2);
        fakes.assertNoPassageDeletion();
        assertThat(logLines()).containsExactly("compte 5 supprimé, 2 coureurs détachés");
        assertNoSecretNorPseudoInLogs(PASSWORD);
    }

    @Test
    @DisplayName("CA33 - suppression d'un compte inexistant : ResourceNotFoundException, rien de supprime")
    void ca33_deleteUnknownAccount() {
        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(accountFakes.deletedAccounts()).isEmpty();
        assertThat(logLines()).isEmpty();
    }

    @Test
    @DisplayName("CA33 / CL16 - pseudo libere par la suppression : un nouveau compte peut le reprendre")
    void cl16_pseudoFreedAfterDeletion() {
        existingLievre();
        service.delete(5L);

        Account again = service.create("Lievre", "motdepasse-9");

        assertThat(again.getPseudo()).isEqualTo("lievre");
        assertThat(again.getId()).isNotEqualTo(5L);
    }

    // ----- liste et recherche (RG24) -----

    private void lievreOublieTortue() {
        Account lievre = account(5L, "lievre", "$2a$04$h1");
        Account oublie = account(6L, "oublie", "$2a$04$h2");
        Account tortue = account(7L, "tortue", "$2a$04$h3");
        accountFakes.withAccounts(tortue, lievre, oublie);
        fakes.withRunners(runner(60L, r1, 1, lievre, "t1"), runner(61L, r2, 1, lievre, "t2"),
            runner(62L, r1, 2, tortue, "t3"));
    }

    @Test
    @DisplayName("CA42 - sans filtre : lievre, oublie, tortue avec runnerCount 2, 0, 1")
    void ca42_allAccounts() {
        lievreOublieTortue();

        assertThat(service.search(null))
            .extracting(AccountSummaryView::accountId, AccountSummaryView::pseudo, AccountSummaryView::runnerCount)
            .containsExactly(tuple(5L, "lievre", 2L), tuple(6L, "oublie", 0L), tuple(7L, "tortue", 1L));
    }

    @Test
    @DisplayName("CA42 - filtre vide ou blanc : les 3 comptes")
    void ca42_blankFilter() {
        lievreOublieTortue();

        assertThat(service.search("")).hasSize(3);
        assertThat(service.search("   ")).hasSize(3);
    }

    @Test
    @DisplayName("CA42 - ?pseudo=LIE : lievre puis oublie (filtre normalise en lie)")
    void ca42_filterIsNormalized() {
        lievreOublieTortue();

        assertThat(service.search("LIE")).extracting(AccountSummaryView::pseudo).containsExactly("lievre", "oublie");
        verify(accountFakes.accountRepository).findByPseudoContainingOrderByPseudoAscIdAsc("lie");
    }

    @Test
    @DisplayName("CA42 - ?pseudo=' tor ' : tortue seul ; ?pseudo=zzz : liste vide")
    void ca42_trimmedFilterAndNoMatch() {
        lievreOublieTortue();

        assertThat(service.search(" tor ")).extracting(AccountSummaryView::pseudo).containsExactly("tortue");
        assertThat(service.search("zzz")).isEmpty();
    }

    private void assertNoSecretNorPseudoInLogs(String secret) {
        assertThat(logLines()).allSatisfy(line -> assertThat(line)
            .doesNotContain(secret)
            .doesNotContain("$2a$")
            .doesNotContain(PASSWORD));
        assertThat(logLines()).allSatisfy(line -> assertThat(line.toLowerCase(Locale.ROOT)).doesNotContain("lievre"));
    }
}
