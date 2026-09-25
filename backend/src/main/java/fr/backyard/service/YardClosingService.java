package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.YardCalculator;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.YardClosingFailedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clôture du dernier yard écoulé de chaque course RUNNING : auto-DNF puis détection de fin de course
 * (RG16 à RG21). La clôture est idempotente (RG20) : un appel interrompu est rattrapé par le suivant.
 */
@Service
public class YardClosingService {

    private final RaceRepository raceRepository;
    private final RunnerRepository runnerRepository;
    private final PassageLookup passageLookup;
    private final Clock clock;
    private final YardCalculator yardCalculator = new YardCalculator();

    public YardClosingService(RaceRepository raceRepository, RunnerRepository runnerRepository,
                              PassageRepository passageRepository, Clock clock) {
        this.raceRepository = raceRepository;
        this.runnerRepository = runnerRepository;
        this.passageLookup = new PassageLookup(passageRepository);
        this.clock = clock;
    }

    /**
     * Point d'entrée périodique (RG17) : clôture chaque course RUNNING avec le même instant.
     * L'échec d'une course n'empêche pas les suivantes ; les échecs sont remontés ensemble à la fin.
     */
    public List<YardClosingResult> closeElapsedYards() {
        Instant now = Instant.now(clock);
        List<YardClosingResult> results = new ArrayList<>();
        Map<Long, RuntimeException> failuresByRaceId = new LinkedHashMap<>();
        for (Race race : raceRepository.findByStatus(RaceStatus.RUNNING)) {
            try {
                results.add(closeYard(race, now));
            } catch (RuntimeException failure) {
                failuresByRaceId.put(race.getId(), failure);
            }
        }
        if (!failuresByRaceId.isEmpty()) {
            throw new YardClosingFailedException(failuresByRaceId);
        }
        return results;
    }

    /** Clôture du yard N = C - 1 de la course (RG16, RG18). */
    public YardClosingResult closeYard(Race race) {
        return closeYard(race, Instant.now(clock));
    }

    private YardClosingResult closeYard(Race race, Instant now) {
        int closedYard = yardCalculator.currentYard(race, now) - 1;
        if (closedYard < 1) {
            return YardClosingResult.nothingToClose(race.getId());
        }
        List<Runner> runners = runnerRepository.findByRaceId(race.getId());
        List<Runner> finishers = runners.stream()
            .filter(runner -> passageLookup.hasValidPassageOnYard(runner, closedYard))
            .toList();
        List<Runner> timedOut = timeOutActiveRunnersWithoutPassage(runners, finishers, closedYard);
        Optional<Runner> winner = electWinner(finishers);
        boolean raceFinished = winner.isPresent() || runners.stream().noneMatch(Runner::isActive);
        if (raceFinished) {
            race.finish();
            raceRepository.save(race);
        }
        return new YardClosingResult(race.getId(), closedYard, idsOf(timedOut), winner.map(Runner::getId),
            raceFinished);
    }

    /** RG19 : tout coureur ACTIVE sans passage sur le yard clôturé passe DNF TIMEOUT. */
    private List<Runner> timeOutActiveRunnersWithoutPassage(List<Runner> runners, List<Runner> finishers,
                                                            int closedYard) {
        List<Runner> timedOut = runners.stream()
            .filter(Runner::isActive)
            .filter(runner -> !finishers.contains(runner))
            .toList();
        timedOut.forEach(runner -> runner.markDnf(DnfReason.TIMEOUT, closedYard));
        if (!timedOut.isEmpty()) {
            runnerRepository.saveAll(timedOut);
        }
        return timedOut;
    }

    /** RG21 : un unique finisher du yard, encore ACTIVE, est vainqueur. */
    private Optional<Runner> electWinner(List<Runner> finishers) {
        if (finishers.size() != 1 || !finishers.get(0).isActive()) {
            return Optional.empty();
        }
        Runner winner = finishers.get(0);
        winner.markWinner();
        runnerRepository.save(winner);
        return Optional.of(winner);
    }

    private static List<Long> idsOf(List<Runner> runners) {
        return runners.stream().map(Runner::getId).toList();
    }
}
