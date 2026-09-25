package fr.backyard.service;

import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.domain.YardCalculator;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RunnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Réintégration d'un coureur DNF, correction d'erreur admin (RG23 à RG26).
 */
@Service
public class ReintegrationService {

    private final RunnerRepository runnerRepository;
    private final PassageRepository passageRepository;
    private final PassageLookup passageLookup;
    private final Clock clock;
    private final YardCalculator yardCalculator = new YardCalculator();

    public ReintegrationService(RunnerRepository runnerRepository, PassageRepository passageRepository, Clock clock) {
        this.runnerRepository = runnerRepository;
        this.passageRepository = passageRepository;
        this.passageLookup = new PassageLookup(passageRepository);
        this.clock = clock;
    }

    /**
     * Recrée les passages manquants de dnf_yard à C - 1 (source MANUAL, sans horodatage),
     * puis remet le coureur en course. Renvoie les passages créés, par yard croissant.
     */
    @Transactional
    public List<Passage> reintegrate(Long runnerId) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        Race race = runner.getRace();
        BusinessGuards.requireRunningRace(race, "réintégrer le coureur " + runnerId);
        BusinessGuards.requireRunnerStatus(runner, RunnerStatus.DNF, "réintégrer");
        int lastClosedYard = yardCalculator.currentYard(race, Instant.now(clock)) - 1;
        List<Passage> recreated = recreateMissingPassages(runner, requireDnfYard(runner), lastClosedYard);
        runner.reactivate();
        runnerRepository.save(runner);
        return recreated;
    }

    private List<Passage> recreateMissingPassages(Runner runner, int fromYard, int toYard) {
        List<Passage> recreated = new ArrayList<>();
        for (int yard = fromYard; yard <= toYard; yard++) {
            if (!passageLookup.hasValidPassageOnYard(runner, yard)) {
                recreated.add(passageRepository.save(new Passage(runner, yard, PassageSource.MANUAL, null)));
            }
        }
        return recreated;
    }

    private static int requireDnfYard(Runner runner) {
        Integer dnfYard = runner.getDnfYard();
        if (dnfYard == null) {
            throw new IllegalStateException("Donnée incohérente : coureur " + runner.getId()
                + " au statut DNF sans dnf_yard");
        }
        return dnfYard;
    }
}
