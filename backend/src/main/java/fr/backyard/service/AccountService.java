package fr.backyard.service;

import fr.backyard.domain.Account;
import fr.backyard.domain.PasswordPolicy;
import fr.backyard.domain.Pseudo;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.repository.AccountRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Comptes coureurs « pseudo » (inc. 5) : création (RG5, RG7), authentification (RG9), inscriptions du compte (RG11),
 * changement (RG19) et réinitialisation (RG14) du mot de passe, suppression (RG20), liste et recherche (RG24).
 * Tout pseudo reçu passe par {@link Pseudo#normalize(String)} avant enregistrement ou recherche (RG2). Les journaux
 * ne contiennent ni pseudo, ni mot de passe, ni hash (RG16).
 */
@Service
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    /** RG11 : inscriptions par date de course croissante, puis id de course. */
    private static final Comparator<AccountRegistrationView> REGISTRATION_ORDER =
        Comparator.comparing(AccountRegistrationView::raceDate).thenComparing(AccountRegistrationView::raceId);

    private final AccountRepository accountRepository;
    private final RunnerRepository runnerRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository accountRepository, RunnerRepository runnerRepository,
                          PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.runnerRepository = runnerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * RG5, RG7 (étapes 4 et 5) : crée un compte au pseudo normalisé, refusé en 409 si ce pseudo existe déjà,
     * quelle que soit la course, même avec le bon mot de passe.
     */
    @Transactional
    public Account create(String pseudoInput, String password) {
        requireWellFormedPseudo(pseudoInput);
        requireAcceptablePassword(password);
        String pseudo = Pseudo.normalize(pseudoInput);
        if (accountRepository.existsByPseudo(pseudo)) {
            throw new BusinessConflictException("Pseudo déjà utilisé : " + pseudo
                + ". Si c'est votre compte, connectez-vous pour vous inscrire avec.");
        }
        return accountRepository.save(new Account(pseudo, passwordEncoder.encode(password)));
    }

    /** 404 si le compte n'existe pas. */
    @Transactional(readOnly = true)
    public Account get(Long accountId) {
        return BusinessGuards.requireAccount(accountRepository, accountId);
    }

    /** RG9 : le pseudo présenté est normalisé, puis recherché à l'identique ; vide si aucun compte ne le porte. */
    @Transactional(readOnly = true)
    public Optional<AccountCredentials> findCredentials(String presentedPseudo) {
        return accountRepository.findByPseudo(Pseudo.normalize(presentedPseudo))
            .map(account -> new AccountCredentials(account.getId(), account.getPseudo(), account.getPasswordHash()));
    }

    /** RG11 : pseudo et inscriptions du compte, avec leurs qr_token. */
    @Transactional(readOnly = true)
    public AccountRegistrationsView registrations(Long accountId) {
        Account account = BusinessGuards.requireAccount(accountRepository, accountId);
        List<AccountRegistrationView> registrations = runnerRepository.findByAccountId(accountId).stream()
            .map(AccountService::registrationView)
            .sorted(REGISTRATION_ORDER)
            .toList();
        return new AccountRegistrationsView(account.getPseudo(), registrations);
    }

    /** RG19 : le titulaire authentifié remplace son mot de passe. */
    @Transactional
    public void changePassword(Long accountId, String newPassword) {
        replacePassword(accountId, newPassword);
        LOG.info("mot de passe modifié par le titulaire du compte {}", accountId);
    }

    /** RG14 : l'admin saisit un mot de passe provisoire, sans obligation de changement ensuite. */
    @Transactional
    public void resetPassword(Long accountId, String newPassword) {
        replacePassword(accountId, newPassword);
        LOG.info("mot de passe réinitialisé pour le compte {}", accountId);
    }

    /**
     * RG20 : détache tous les coureurs du compte (ils gardent dossard, statut, qr_token et passages), puis supprime
     * le compte, dans une seule transaction.
     */
    @Transactional
    public void delete(Long accountId) {
        Account account = BusinessGuards.requireAccount(accountRepository, accountId);
        List<Runner> runners = runnerRepository.findByAccountId(accountId);
        runners.forEach(Runner::detachAccount);
        runnerRepository.saveAll(runners);
        runnerRepository.flush();
        accountRepository.delete(account);
        LOG.info("compte {} supprimé, {} coureurs détachés", accountId, runners.size());
    }

    /**
     * RG24 : filtre normalisé ; sans filtre ou filtre vide, tous les comptes ; sinon les pseudos qui contiennent
     * littéralement le filtre. Tri par pseudo puis par id.
     */
    @Transactional(readOnly = true)
    public List<AccountSummaryView> search(String pseudoFilter) {
        String fragment = pseudoFilter == null ? "" : Pseudo.normalize(pseudoFilter);
        List<Account> accounts = fragment.isEmpty()
            ? accountRepository.findAllByOrderByPseudoAscIdAsc()
            : accountRepository.findByPseudoContainingOrderByPseudoAscIdAsc(fragment);
        return accounts.stream()
            .map(account -> new AccountSummaryView(account.getId(), account.getPseudo(),
                runnerRepository.countByAccountId(account.getId())))
            .toList();
    }

    private void replacePassword(Long accountId, String newPassword) {
        requireAcceptablePassword(newPassword);
        Account account = BusinessGuards.requireAccount(accountRepository, accountId);
        account.replacePasswordHash(passwordEncoder.encode(newPassword));
        accountRepository.save(account);
    }

    private static AccountRegistrationView registrationView(Runner runner) {
        Race race = runner.getRace();
        return new AccountRegistrationView(race.getId(), race.getName(), race.getRaceDate(), race.getStatus(),
            runner.getId(), runner.getBib(), runner.getStatus(), runner.getQrToken());
    }

    private static void requireWellFormedPseudo(String pseudoInput) {
        if (!Pseudo.isWellFormed(pseudoInput)) {
            throw new InvalidInputException("Pseudo invalide : " + Pseudo.FORMAT_RULE);
        }
    }

    private static void requireAcceptablePassword(String password) {
        if (!PasswordPolicy.isAcceptable(password)) {
            throw new InvalidInputException("Mot de passe invalide : " + PasswordPolicy.RULE);
        }
    }
}
