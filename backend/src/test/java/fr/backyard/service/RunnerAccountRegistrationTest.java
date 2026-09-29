package fr.backyard.service;

import fr.backyard.domain.Account;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.AccountFakes;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.util.UUID;

import static fr.backyard.testsupport.AccountFakes.account;
import static fr.backyard.testsupport.AccountFakes.race;
import static fr.backyard.testsupport.AccountFakes.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec increment 5 - RunnerService [unit] : inscription avec creation de compte (RG7, E3), inscription d'un compte
 * existant (RG8, E20), modification du dossard seul (RG18), compte conserve apres suppression du coureur (CL6).
 */
@Tag("INC-5")
class RunnerAccountRegistrationTest {

    private static final String PASSWORD = "motdepasse-1";

    private final FakeRepositories fakes = new FakeRepositories();
    private final AccountFakes accountFakes = new AccountFakes(fakes);
    private final AccountService accountService =
        new AccountService(accountFakes.accountRepository, fakes.runnerRepository, new BCryptPasswordEncoder(4));
    private final RunnerService service = new RunnerService(fakes.raceRepository, fakes.runnerRepository,
        fakes.passageRepository, new QrTokenGenerator(), accountService);

    private final Race r1 = race(1L, "Backyard Test", LocalDate.of(2026, 10, 3), RaceStatus.SETUP);
    private final Race r2 = race(2L, "Backyard Automne", LocalDate.of(2026, 11, 7), RaceStatus.SETUP);

    @BeforeEach
    void races() {
        fakes.withRaces(r1, r2);
    }

    // ----- E3 (RG7) -----

    @Test
    @DisplayName("CA6 - E3 « Lievre » sur R1 : compte lievre cree, coureur bib 1 ACTIVE lie au compte, nom affiche lievre")
    void ca6_registerCreatesAccountAndRunner() {
        Runner runner = service.register(1L, "Lievre", PASSWORD);

        assertThat(accountFakes.accounts()).extracting(Account::getPseudo).containsExactly("lievre");
        assertThat(runner.getRace()).isSameAs(r1);
        assertThat(runner.getBib()).isEqualTo(1);
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(runner.getAccount()).isSameAs(accountFakes.accounts().getFirst());
        assertThat(runner.displayName()).isEqualTo("lievre");
        assertThat(UUID.fromString(runner.getQrToken()).toString()).isEqualTo(runner.getQrToken());
        assertThat(fakes.runners()).containsExactly(runner);
    }

    @Test
    @DisplayName("RG7 - course introuvable : 404 avant toute creation de compte")
    void rg7_unknownRace() {
        assertThatThrownBy(() -> service.register(99L, "Lievre", PASSWORD))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Course introuvable : id 99");

        assertThat(accountFakes.accounts()).isEmpty();
        assertThat(fakes.runners()).isEmpty();
    }

    @Test
    @DisplayName("RG7 - inscriptions fermees : 409 avant le controle du pseudo, aucun compte cree")
    void rg7_closedRegistrationBeforePseudoCheck() {
        r1.setStatus(RaceStatus.RUNNING);
        accountFakes.withAccounts(account(5L, "lievre", "$2a$04$h"));

        assertThatThrownBy(() -> service.register(1L, "Lievre", PASSWORD))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessage("Inscriptions fermées : course 1 au statut RUNNING");

        assertThat(accountFakes.accounts()).hasSize(1);
        assertThat(fakes.runners()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - pseudo deja pris en R1, E3 sur R2 : 409, R2 sans coureur, un seul compte")
    void ca7_pseudoTakenInAnotherRace() {
        service.register(1L, "Lievre", PASSWORD);

        assertThatThrownBy(() -> service.register(2L, "LIEVRE", PASSWORD))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining("lievre");

        assertThat(accountFakes.accounts()).hasSize(1);
        assertThat(fakes.runners()).extracting(runner -> runner.getRace().getId()).containsExactly(1L);
    }

    @Test
    @DisplayName("CA29 - Lievre puis Lievre2 sur R1 : deux comptes, dossards 1 et 2")
    void ca29_twoAccountsSameRace() {
        Runner first = service.register(1L, "Lievre", PASSWORD);
        Runner second = service.register(1L, "Lievre2", PASSWORD);

        assertThat(first.getBib()).isEqualTo(1);
        assertThat(second.getBib()).isEqualTo(2);
        assertThat(first.getAccount()).isNotSameAs(second.getAccount());
        assertThat(accountFakes.accounts()).extracting(Account::getPseudo).containsExactly("lievre", "lievre2");
    }

    // ----- E20 (RG8) -----

    @Test
    @DisplayName("CA9 - compte A inscrit en R1 s'inscrit en R2 : coureur bib 1 lie a A ; A a 2 coureurs")
    void ca9_registerExistingAccountInAnotherRace() {
        Runner inR1 = service.register(1L, "Lievre", PASSWORD);
        Long accountId = inR1.accountId();

        Runner inR2 = service.registerAccount(2L, accountId);

        assertThat(inR2.getRace()).isSameAs(r2);
        assertThat(inR2.getBib()).isEqualTo(1);
        assertThat(inR2.accountId()).isEqualTo(accountId);
        assertThat(fakes.runners()).filteredOn(runner -> accountId.equals(runner.accountId())).hasSize(2);
        assertThat(accountFakes.accounts()).hasSize(1);
    }

    @Test
    @DisplayName("CA9 - deja inscrit a cette course : 409 « Déjà inscrit à cette course », aucun coureur cree")
    void ca9_alreadyRegistered() {
        Runner inR1 = service.register(1L, "Lievre", PASSWORD);

        assertThatThrownBy(() -> service.registerAccount(1L, inR1.accountId()))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessage("Déjà inscrit à cette course");

        assertThat(fakes.runners()).containsExactly(inR1);
    }

    @Test
    @DisplayName("CA9 - course passee en RUNNING : 409 inscriptions fermees pour un compte non inscrit")
    void ca9_closedRace() {
        Account b = account(8L, "tortue", "$2a$04$h");
        accountFakes.withAccounts(b);
        r1.setStatus(RaceStatus.RUNNING);

        assertThatThrownBy(() -> service.registerAccount(1L, 8L))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessage("Inscriptions fermées : course 1 au statut RUNNING");

        assertThat(fakes.runners()).isEmpty();
    }

    @Test
    @DisplayName("RG8 - course introuvable : 404 ; compte disparu : 404")
    void rg8_notFound() {
        accountFakes.withAccounts(account(8L, "tortue", "$2a$04$h"));

        assertThatThrownBy(() -> service.registerAccount(99L, 8L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Course introuvable : id 99");
        assertThatThrownBy(() -> service.registerAccount(1L, 42L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Compte introuvable : id 42");
        assertThat(fakes.runners()).isEmpty();
    }

    @Test
    @DisplayName("RG8 - dossard = plus grand + 1, y compris apres des coureurs sans compte")
    void rg8_nextBibAfterRunnersWithoutAccount() {
        fakes.withRunners(runner(70L, r2, 4, null, "tok-legacy"));
        accountFakes.withAccounts(account(8L, "tortue", "$2a$04$h"));

        assertThat(service.registerAccount(2L, 8L).getBib()).isEqualTo(5);
    }

    // ----- E15 (RG18) et E16 (CL6) -----

    @Test
    @DisplayName("CA30 - E15 ne modifie que le dossard : nom affiche toujours le pseudo")
    void ca30_updateChangesOnlyBib() {
        Runner runner = service.register(1L, "Lievre", PASSWORD);

        Runner updated = service.update(runner.getId(), 2);

        assertThat(updated.getBib()).isEqualTo(2);
        assertThat(updated.displayName()).isEqualTo("lievre");
    }

    @Test
    @DisplayName("CA34 - E15 sur un coureur sans compte : le nom affiche suit le nouveau dossard")
    void ca34_updateBibOfRunnerWithoutAccount() {
        Runner detached = runner(71L, r2, 1, null, "tok-detached");
        fakes.withRunners(detached);

        Runner updated = service.update(71L, 5);

        assertThat(updated.displayName()).isEqualTo("Coureur n°5");
    }

    @Test
    @DisplayName("CA23 / CL6 - suppression du coureur : le compte subsiste, liste vide, nouvelle inscription possible en R2")
    void ca23_accountSurvivesRunnerDeletion() {
        Runner runner = service.register(1L, "Lievre", PASSWORD);
        Long accountId = runner.accountId();

        service.delete(runner.getId());

        assertThat(accountFakes.accounts()).extracting(Account::getId).containsExactly(accountId);
        assertThat(accountService.registrations(accountId).registrations()).isEmpty();
        assertThat(service.registerAccount(2L, accountId).getBib()).isEqualTo(1);
    }
}
