package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec increment 5 - RG4, RG12, RG18, RG20 [unit] : lien au compte et nom affiche d'un coureur. */
@Tag("INC-5")
class RunnerAccountLinkTest {

    private final Race race = new Race("Backyard Test", LocalDate.of(2026, 10, 3), 6706, 3600, 50);

    private static Account account(long id, String pseudo) {
        Account account = new Account(pseudo, "$2a$04$hash");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @Test
    @DisplayName("RG18 - coureur lie a un compte : nom affiche = pseudo stocke ; accountId et pseudo exposes (RG12)")
    void rg18_displayNameIsAccountPseudo() {
        Runner runner = new Runner(race, 1, account(5L, "lievre"), "tok-1");

        assertThat(runner.displayName()).isEqualTo("lievre");
        assertThat(runner.accountId()).isEqualTo(5L);
        assertThat(runner.accountPseudo()).isEqualTo("lievre");
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("RG18 - coureur sans compte : « Coureur n°{bib} », sans zero de tete ; accountId et pseudo null")
    void rg18_displayNameWithoutAccount() {
        Runner runner = new Runner(race, 12, "tok-12");

        assertThat(runner.displayName()).isEqualTo("Coureur n°12");
        assertThat(runner.getAccount()).isNull();
        assertThat(runner.accountId()).isNull();
        assertThat(runner.accountPseudo()).isNull();
    }

    @Test
    @DisplayName("RG18 - le libelle d'un coureur sans compte suit le nouveau dossard")
    void rg18_displayNameFollowsBib() {
        Runner runner = new Runner(race, 1, "tok-1");

        runner.setBib(5);

        assertThat(runner.displayName()).isEqualTo("Coureur n°5");
    }

    @Test
    @DisplayName("RG20 - detachement : le compte passe a null, nom affiche « Coureur n°{bib} », dossard, statut et token conserves")
    void rg20_detachAccount() {
        Runner runner = new Runner(race, 1, account(5L, "lievre"), "tok-1");
        runner.markDnf(DnfReason.TIMEOUT, 2);

        runner.detachAccount();

        assertThat(runner.getAccount()).isNull();
        assertThat(runner.displayName()).isEqualTo("Coureur n°1");
        assertThat(runner.getBib()).isEqualTo(1);
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(runner.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(runner.getDnfYard()).isEqualTo(2);
        assertThat(runner.getQrToken()).isEqualTo("tok-1");
    }

    @Test
    @DisplayName("RG1 / RG3 - compte : seul le hash change ; toString ne revele ni pseudo ni hash")
    void rg1_accountReplacesOnlyItsHash() {
        Account account = account(5L, "lievre");

        account.replacePasswordHash("$2a$04$autre");

        assertThat(account.getPseudo()).isEqualTo("lievre");
        assertThat(account.getPasswordHash()).isEqualTo("$2a$04$autre");
        assertThat(account.toString()).doesNotContain("lievre").doesNotContain("$2a$");
    }
}
