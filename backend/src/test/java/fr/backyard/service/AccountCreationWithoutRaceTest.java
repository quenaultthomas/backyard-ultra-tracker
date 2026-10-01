package fr.backyard.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.domain.Account;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.testsupport.AccountFakes;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static fr.backyard.testsupport.AccountFakes.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Spec increment 7 [unit] : l'endpoint E26 s'appuie sur {@link AccountService#create} sans course ni coureur
 * (RG5, CA1, CA3, CL5, CL7, CA18). Depots en memoire, vrai BCrypt de cout 4, aucune base, aucun contexte Spring.
 * Ces tests prouvent que le service existant suffit a E26 : aucune seconde regle de creation n'est requise.
 */
@Tag("INC-7")
class AccountCreationWithoutRaceTest {

    private static final String PASSWORD = "motdepasse-9";
    private static final String CONFLICT_DETAIL =
        "Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec.";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final FakeRepositories fakes = new FakeRepositories();
    private final AccountFakes accountFakes = new AccountFakes(fakes);
    private final AccountService service =
        new AccountService(accountFakes.accountRepository, fakes.runnerRepository, passwordEncoder);

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(AccountService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.TRACE);
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("CA1 - creation sans aucune course ni coureur : un compte au pseudo normalise (« Nouveau-1 » avec espaces -> nouveau-1), hash BCrypt verifiant le mot de passe")
    void ca1_createsAnAccountWithoutAnyRace() {
        Account created = service.create("  Nouveau-1 ", PASSWORD);

        assertThat(accountFakes.accounts()).containsExactly(created);
        assertThat(created.getPseudo()).isEqualTo("nouveau-1");
        assertThat(created.getPasswordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, created.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("CA1 - la creation n'ecrit aucun coureur (0 runner) : le depot de coureurs n'est jamais sollicite en ecriture")
    void ca1_createsNoRunner() {
        service.create("nouveau-1", PASSWORD);

        assertThat(fakes.runners()).isEmpty();
        verify(fakes.runnerRepository, never()).save(any());
        verify(fakes.runnerRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("CL7 - compte cree sans inscription : registrations() renvoie le pseudo et une liste vide ; search() le liste avec 0 coureur")
    void cl7_accountWithoutRegistrationIsHandled() {
        Account created = service.create("nouveau-1", PASSWORD);

        AccountRegistrationsView view = service.registrations(created.getId());
        assertThat(view.pseudo()).isEqualTo("nouveau-1");
        assertThat(view.registrations()).isEmpty();
        assertThat(service.search("nouveau")).extracting(AccountSummaryView::pseudo, AccountSummaryView::runnerCount)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("nouveau-1", 0L));
    }

    @Test
    @DisplayName("CA3 - pseudo pris (Lievre, LIEVRE, lievre) : 409 avec le detail exact du service, un seul compte conserve, meme avec le bon mot de passe")
    void ca3_conflictMessageAndSingleAccount() {
        accountFakes.withAccounts(account(5L, "lievre", passwordEncoder.encode("motdepasse-1")));

        for (String input : List.of("Lievre", "LIEVRE", "lievre")) {
            assertThatThrownBy(() -> service.create(input, "motdepasse-1"))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage(CONFLICT_DETAIL);
        }

        assertThat(accountFakes.accounts()).extracting(Account::getPseudo).containsExactly("lievre");
        assertThat(accountFakes.savedAccounts()).isEmpty();
    }

    @Test
    @DisplayName("CL5 - un pseudo egal a un nom de compte de configuration (admin-test, scanner-test) est accepte : aucun controle sur le staff")
    void cl5_staffLikePseudoIsAccepted() {
        assertThat(service.create("admin-test", PASSWORD).getPseudo()).isEqualTo("admin-test");
        assertThat(service.create("Scanner-Test", PASSWORD).getPseudo()).isEqualTo("scanner-test");
    }

    @Test
    @DisplayName("CA2/RG5 - ordre des controles : format du pseudo (400) avant le conflit ; mot de passe invalide sur pseudo deja pris reste 400, pas 409")
    void rg5_validationPrecedesConflict() {
        accountFakes.withAccounts(account(5L, "lievre", passwordEncoder.encode("motdepasse-1")));

        assertThatThrownBy(() -> service.create("li", PASSWORD)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.create("Lievre", "court12")).isInstanceOf(InvalidInputException.class);
        assertThat(accountFakes.savedAccounts()).isEmpty();
    }

    @Test
    @DisplayName("CA18 - creation, conflit et validation en echec : aucune ligne du service ne contient pseudo, mot de passe ni hash")
    void ca18_noPseudoNorSecretInServiceLogs() {
        accountFakes.withAccounts(account(5L, "lievre", passwordEncoder.encode("motdepasse-1")));

        Account created = service.create("nouveau-1", PASSWORD);
        assertThatThrownBy(() -> service.create("Lievre", PASSWORD)).isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> service.create("ab", PASSWORD)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.create("nouveau-2", "court12")).isInstanceOf(InvalidInputException.class);

        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("nouveau-1").doesNotContain("nouveau-2")
                .doesNotContain("lievre").doesNotContain(PASSWORD).doesNotContain("court12")
                .doesNotContain("$2").doesNotContain(created.getPasswordHash());
        }
    }
}
