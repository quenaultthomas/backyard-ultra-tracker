package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatsCalculator;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.InvalidInputException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * DNF manuel déclaré par un admin (RG22). La confirmation est un sujet API/UI (incréments 3 et 4).
 */
@Service
public class ManualDnfService {

    private final RunnerRepository runnerRepository;
    private final PassageLookup passageLookup;
    private final RunnerStatsCalculator statsCalculator = new RunnerStatsCalculator();

    /**
     * La {@link Clock} fait partie du contrat commun des services (RG1) mais n'est pas lue :
     * le dnf_yard d'un DNF manuel ne dépend que des passages du coureur (RG22, PO3).
     */
    public ManualDnfService(RunnerRepository runnerRepository, PassageRepository passageRepository, Clock clock) {
        this.runnerRepository = runnerRepository;
        this.passageLookup = new PassageLookup(passageRepository);
    }

    @Transactional
    public Runner declareDnf(Long runnerId, DnfReason reason) {
        requireManualReason(reason);
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        BusinessGuards.requireRunningRace(runner.getRace(), "déclarer le coureur " + runnerId + " DNF");
        BusinessGuards.requireRunnerStatus(runner, RunnerStatus.ACTIVE, "déclarer DNF");
        int dnfYard = statsCalculator.firstUnfinishedYard(passageLookup.allOf(runner));
        runner.markDnf(reason, dnfYard);
        return runnerRepository.save(runner);
    }

    private static void requireManualReason(DnfReason reason) {
        if (reason == null || reason == DnfReason.TIMEOUT) {
            throw new InvalidInputException("Raison de DNF manuel invalide : " + reason
                + " (attendu VOLUNTARY, MANUAL ou OTHER ; TIMEOUT est réservé à l'auto-DNF)");
        }
    }
}
