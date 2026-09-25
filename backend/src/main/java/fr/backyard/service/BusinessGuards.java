package fr.backyard.service;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;

/**
 * Préconditions communes aux services métier, avec messages d'erreur explicites (RG28).
 */
final class BusinessGuards {

    private BusinessGuards() {
    }

    static Runner requireRunner(RunnerRepository runnerRepository, Long runnerId) {
        return runnerRepository.findById(runnerId)
            .orElseThrow(() -> new ResourceNotFoundException("Coureur introuvable : id " + runnerId));
    }

    static void requireRunningRace(Race race, String action) {
        if (!race.isRunning()) {
            throw new BusinessConflictException("Impossible de " + action + " : la course " + race.getId()
                + " est au statut " + race.getStatus() + " (attendu " + RaceStatus.RUNNING + ")");
        }
    }

    static void requireRunnerStatus(Runner runner, RunnerStatus expected, String action) {
        if (runner.getStatus() != expected) {
            throw new BusinessConflictException("Impossible de " + action + " : le coureur " + runner.getId()
                + " est au statut " + runner.getStatus() + " (attendu " + expected + ")");
        }
    }
}
