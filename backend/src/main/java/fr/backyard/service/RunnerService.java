package fr.backyard.service;

import fr.backyard.domain.Account;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestion des coureurs : inscription avec compte (RG7, RG8 inc. 5), lecture, modification du dossard, suppression
 * (RG15 à RG19 inc. 3).
 */
@Service
public class RunnerService {

    private final RaceRepository raceRepository;
    private final RunnerRepository runnerRepository;
    private final PassageRepository passageRepository;
    private final QrTokenGenerator qrTokenGenerator;
    private final AccountService accountService;

    public RunnerService(RaceRepository raceRepository, RunnerRepository runnerRepository,
                         PassageRepository passageRepository, QrTokenGenerator qrTokenGenerator,
                         AccountService accountService) {
        this.raceRepository = raceRepository;
        this.runnerRepository = runnerRepository;
        this.passageRepository = passageRepository;
        this.qrTokenGenerator = qrTokenGenerator;
        this.accountService = accountService;
    }

    /**
     * RG7 inc. 5 (E3) : course introuvable (404), inscriptions fermées (409), pseudo déjà porté (409), puis création
     * du compte et de son coureur dans la même transaction.
     */
    @Transactional
    public Runner register(Long raceId, String pseudo, String password) {
        Race race = requireRegistrableRace(raceId);
        Account account = accountService.create(pseudo, password);
        return createRunner(race, account);
    }

    /**
     * RG8 inc. 5 (E20) : inscription d'un compte existant ; course introuvable (404), inscriptions fermées (409),
     * compte déjà inscrit à cette course (409).
     */
    @Transactional
    public Runner registerAccount(Long raceId, Long accountId) {
        Race race = requireRegistrableRace(raceId);
        Account account = accountService.get(accountId);
        if (runnerRepository.existsByRaceIdAndAccountId(raceId, accountId)) {
            throw new BusinessConflictException("Déjà inscrit à cette course");
        }
        return createRunner(race, account);
    }

    /** RG17 : coureurs d'une course existante, par dossard croissant. */
    @Transactional(readOnly = true)
    public List<Runner> listByRace(Long raceId) {
        BusinessGuards.requireRace(raceRepository, raceId);
        return runnerRepository.findByRaceId(raceId).stream().sorted(Runner.BIB_ORDER).toList();
    }

    @Transactional(readOnly = true)
    public Runner get(Long runnerId) {
        return BusinessGuards.requireRunner(runnerRepository, runnerId);
    }

    /**
     * RG18 inc. 3 et 5 : seul le dossard se modifie, uniquement en SETUP (PO11 inc. 3) et s'il est libre dans la
     * course. Le nom affiché est dérivé et ne se modifie pas.
     */
    @Transactional
    public Runner update(Long runnerId, int bib) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        if (bib != runner.getBib()) {
            requireBibChangeAllowed(runner, bib);
            runner.setBib(bib);
        }
        return runnerRepository.save(runner);
    }

    /** RG19 : suppression d'un coureur sans passage, course SETUP uniquement. Le compte éventuel subsiste. */
    @Transactional
    public void delete(Long runnerId) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        BusinessGuards.requireSetupRace(runner.getRace(),
            "supprimer le coureur " + runnerId + " (un abandon en course passe par le DNF manuel)");
        if (passageRepository.existsByRunnerId(runnerId)) {
            throw new BusinessConflictException("Impossible de supprimer le coureur " + runnerId
                + " : il a des passages, qui sont immuables");
        }
        runnerRepository.delete(runner);
    }

    private Race requireRegistrableRace(Long raceId) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        BusinessGuards.requireRegistrationOpen(race);
        return race;
    }

    /** RG15, RG16 inc. 3 : dossard = plus grand + 1 (PO8), qr_token opaque ; lié au compte (RG4 inc. 5). */
    private Runner createRunner(Race race, Account account) {
        int bib = nextBib(runnerRepository.findByRaceId(race.getId()));
        return runnerRepository.save(new Runner(race, bib, account, qrTokenGenerator.generate()));
    }

    private void requireBibChangeAllowed(Runner runner, int bib) {
        Race race = runner.getRace();
        BusinessGuards.requireSetupRace(race, "modifier le dossard du coureur " + runner.getId());
        if (runnerRepository.existsByRaceIdAndBibAndIdNot(race.getId(), bib, runner.getId())) {
            throw new BusinessConflictException("Le dossard " + bib + " est déjà attribué dans la course "
                + race.getId());
        }
    }

    private static int nextBib(List<Runner> runnersOfRace) {
        return runnersOfRace.stream().mapToInt(Runner::getBib).max().orElse(0) + 1;
    }
}
