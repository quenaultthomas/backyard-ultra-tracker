package fr.backyard.service;

import fr.backyard.domain.Passage;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStats;
import fr.backyard.domain.RunnerStatsCalculator;
import fr.backyard.domain.YardCalculator;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lecture des valeurs dérivées (RG24, RG26, RG27 inc. 3) : tableau de bord d'une course et détail
 * d'un coureur. Toutes les valeurs dérivées viennent de {@link YardCalculator} et {@link RunnerStatsCalculator}.
 */
@Service
@Transactional(readOnly = true)
public class RaceBoardService {

    private final RaceRepository raceRepository;
    private final RunnerRepository runnerRepository;
    private final PassageRepository passageRepository;
    private final Clock clock;
    private final YardCalculator yardCalculator = new YardCalculator();
    private final RunnerStatsCalculator statsCalculator = new RunnerStatsCalculator();

    public RaceBoardService(RaceRepository raceRepository, RunnerRepository runnerRepository,
                            PassageRepository passageRepository, Clock clock) {
        this.raceRepository = raceRepository;
        this.runnerRepository = runnerRepository;
        this.passageRepository = passageRepository;
        this.clock = clock;
    }

    /** RG24 : yard courant, heure de la prochaine cloche et statistiques de chaque coureur. */
    public RaceBoardView board(Long raceId) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        Instant now = Instant.now(clock);
        int currentYard = yardCalculator.currentYard(race, now);
        Instant currentYardEndsAt = currentYard >= 1 ? yardCalculator.yardEnd(race, currentYard) : null;
        Map<Long, List<Passage>> passagesByRunnerId = passageRepository.findByRunnerRaceId(raceId).stream()
            .collect(Collectors.groupingBy(passage -> passage.getRunner().getId()));
        List<RunnerBoardEntry> entries = runnerRepository.findByRaceId(raceId).stream()
            .sorted(Runner.BIB_ORDER)
            .map(runner -> boardEntry(race, runner, passagesByRunnerId.getOrDefault(runner.getId(), List.of())))
            .toList();
        return new RaceBoardView(race, now, currentYard, currentYardEndsAt, entries);
    }

    /** RG26 : statistiques et passages d'un coureur, par yard croissant. */
    public RunnerDetailView runnerDetail(Long runnerId) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        Race race = runner.getRace();
        List<Passage> passages = passageRepository.findByRunnerId(runnerId);
        RunnerStats stats = statsCalculator.compute(race, passages);
        return new RunnerDetailView(runner.getId(), race.getId(), runner.getBib(), runner.getName(),
            runner.getStatus(), runner.getDnfReason(), runner.getDnfYard(), stats.completedLoops(),
            stats.distanceMeters(), stats.elevationMeters(), stats.averagePaceSecondsPerKm(), stats.corrected(),
            passageViews(race, passages));
    }

    /**
     * Vues de passages d'un coureur existant, calculées avec les paramètres de sa course
     * (utilisé pour décrire les passages recréés par une réintégration, RG22 inc. 3).
     */
    public List<PassageView> describePassages(Long runnerId, List<Passage> passages) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        return passageViews(runner.getRace(), passages);
    }

    private RunnerBoardEntry boardEntry(Race race, Runner runner, List<Passage> passages) {
        RunnerStats stats = statsCalculator.compute(race, passages);
        return new RunnerBoardEntry(runner.getId(), runner.getBib(), runner.getName(), runner.getStatus(),
            runner.getDnfReason(), runner.getDnfYard(), stats.completedLoops(), stats.distanceMeters(),
            stats.elevationMeters(), stats.averagePaceSecondsPerKm(), stats.corrected());
    }

    private List<PassageView> passageViews(Race race, List<Passage> passages) {
        return passages.stream()
            .sorted(Passage.YARD_ORDER)
            .map(passage -> new PassageView(passage.getYardNumber(), passage.getSource(), passage.getScannedAt(),
                statsCalculator.loopTimeMillis(race, passage), RunnerStatsCalculator.isCorrected(passage)))
            .toList();
    }
}
