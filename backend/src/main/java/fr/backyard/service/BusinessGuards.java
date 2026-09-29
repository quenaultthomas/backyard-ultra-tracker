package fr.backyard.service;

import fr.backyard.domain.Account;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.AccountRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;

/**
 * Préconditions communes aux services métier, avec messages d'erreur explicites (RG28).
 */
final class BusinessGuards {

    private BusinessGuards() {
    }

    static Race requireRace(RaceRepository raceRepository, Long raceId) {
        return raceRepository.findById(raceId)
            .orElseThrow(() -> new ResourceNotFoundException("Course introuvable : id " + raceId));
    }

    static Runner requireRunner(RunnerRepository runnerRepository, Long runnerId) {
        return runnerRepository.findById(runnerId)
            .orElseThrow(() -> new ResourceNotFoundException("Coureur introuvable : id " + runnerId));
    }

    static Account requireAccount(AccountRepository accountRepository, Long accountId) {
        return accountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable : id " + accountId));
    }

    /** RG14 inc. 3 : inscriptions ouvertes, sinon 409. Unique contrôle, pour toute inscription (E3, E20). */
    static void requireRegistrationOpen(Race race) {
        if (!race.isRegistrationOpen()) {
            throw new BusinessConflictException("Inscriptions fermées : course " + race.getId()
                + " au statut " + race.getStatus());
        }
    }

    static void requireRunningRace(Race race, String action) {
        requireRaceStatus(race, RaceStatus.RUNNING, action);
    }

    static void requireSetupRace(Race race, String action) {
        requireRaceStatus(race, RaceStatus.SETUP, action);
    }

    private static void requireRaceStatus(Race race, RaceStatus expected, String action) {
        if (race.getStatus() != expected) {
            throw new BusinessConflictException("Impossible de " + action + " : la course " + race.getId()
                + " est au statut " + race.getStatus() + " (attendu " + expected + ")");
        }
    }

    static void requireRunnerStatus(Runner runner, RunnerStatus expected, String action) {
        if (runner.getStatus() != expected) {
            throw new BusinessConflictException("Impossible de " + action + " : le coureur " + runner.getId()
                + " est au statut " + runner.getStatus() + " (attendu " + expected + ")");
        }
    }
}
