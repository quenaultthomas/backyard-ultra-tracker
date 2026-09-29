package fr.backyard.testsupport;

import fr.backyard.domain.Account;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.repository.AccountRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Doublures de l'increment 5 : {@link AccountRepository} mocke avec Mockito, adosse a un etat en memoire, et
 * requetes de {@code RunnerRepository} ajoutees par l'increment 5, derivees des coureurs de {@link FakeRepositories}.
 * Les comparaisons de pseudos sont exactes, comme en base (RG2 : les pseudos sont stockes normalises).
 * Aucune base de donnees, aucun contexte Spring.
 */
public final class AccountFakes {

    private static final Comparator<Account> PSEUDO_THEN_ID =
        Comparator.comparing(Account::getPseudo).thenComparing(Account::getId);

    public final AccountRepository accountRepository = mock(AccountRepository.class);

    private final List<Account> accounts = new ArrayList<>();
    private final List<Account> savedAccounts = new ArrayList<>();
    private final List<Account> deletedAccounts = new ArrayList<>();
    private long nextAccountId = 700L;

    public AccountFakes(FakeRepositories fakes) {
        stubAccountRepository();
        stubRunnerQueries(fakes);
    }

    /** Compte de test d'identifiant explicite, au pseudo deja normalise. */
    public static Account account(long id, String pseudo, String passwordHash) {
        Account account = new Account(pseudo, passwordHash);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    /** Course de test (6706 m, 3600 s, 50 m D+) au statut donne. */
    public static Race race(long id, String name, LocalDate raceDate, RaceStatus status) {
        Race race = new Race(name, raceDate, 6706, 3600, 50);
        ReflectionTestUtils.setField(race, "id", id);
        race.setStatus(status);
        return race;
    }

    /** Coureur de test d'identifiant explicite, lie ou non a un compte. */
    public static Runner runner(long id, Race race, int bib, Account account, String qrToken) {
        Runner runner = new Runner(race, bib, account, qrToken);
        ReflectionTestUtils.setField(runner, "id", id);
        return runner;
    }

    public AccountFakes withAccounts(Account... newAccounts) {
        for (Account account : newAccounts) {
            if (accounts.stream().noneMatch(existing -> existing == account)) {
                accounts.add(account);
            }
        }
        return this;
    }

    public List<Account> accounts() {
        return List.copyOf(accounts);
    }

    public List<Account> savedAccounts() {
        return List.copyOf(savedAccounts);
    }

    public List<Account> deletedAccounts() {
        return List.copyOf(deletedAccounts);
    }

    private void stubAccountRepository() {
        when(accountRepository.findById(any())).thenAnswer(inv -> accounts.stream()
            .filter(account -> Objects.equals(account.getId(), inv.getArgument(0)))
            .findFirst());
        when(accountRepository.findByPseudo(any())).thenAnswer(inv -> accounts.stream()
            .filter(account -> Objects.equals(account.getPseudo(), inv.getArgument(0)))
            .findFirst());
        when(accountRepository.existsByPseudo(any())).thenAnswer(inv -> accounts.stream()
            .anyMatch(account -> Objects.equals(account.getPseudo(), inv.getArgument(0))));
        when(accountRepository.findAllByOrderByPseudoAscIdAsc())
            .thenAnswer(inv -> new ArrayList<>(accounts.stream().sorted(PSEUDO_THEN_ID).toList()));
        when(accountRepository.findByPseudoContainingOrderByPseudoAscIdAsc(any())).thenAnswer(inv -> {
            String fragment = inv.getArgument(0);
            return new ArrayList<>(accounts.stream()
                .filter(account -> account.getPseudo().contains(fragment))
                .sorted(PSEUDO_THEN_ID)
                .toList());
        });
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            savedAccounts.add(account);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", nextAccountId++);
                accounts.add(account);
            }
            return account;
        });
        doAnswer(inv -> {
            Account account = inv.getArgument(0);
            accounts.remove(account);
            deletedAccounts.add(account);
            return null;
        }).when(accountRepository).delete(any(Account.class));
    }

    private static void stubRunnerQueries(FakeRepositories fakes) {
        when(fakes.runnerRepository.findByAccountId(any())).thenAnswer(inv -> new ArrayList<>(fakes.runners().stream()
            .filter(runner -> Objects.equals(runner.accountId(), inv.getArgument(0)))
            .toList()));
        when(fakes.runnerRepository.existsByRaceIdAndAccountId(any(), any())).thenAnswer(inv -> fakes.runners()
            .stream()
            .anyMatch(runner -> Objects.equals(runner.getRace().getId(), inv.getArgument(0))
                && Objects.equals(runner.accountId(), inv.getArgument(1))));
        when(fakes.runnerRepository.countByAccountId(anyLong())).thenAnswer(inv -> fakes.runners().stream()
            .filter(runner -> Objects.equals(runner.accountId(), inv.getArgument(0)))
            .count());
    }
}
