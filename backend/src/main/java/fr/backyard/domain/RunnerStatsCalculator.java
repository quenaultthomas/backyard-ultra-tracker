package fr.backyard.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Statistiques dérivées d'un coureur (RG5 à RG11) et premier yard non terminé (RG22).
 * Fonctions pures : seuls les paramètres de la course et les passages sont utilisés.
 */
public class RunnerStatsCalculator {

    private final YardCalculator yardCalculator = new YardCalculator();

    public RunnerStatsCalculator() {
        // calculateur sans état
    }

    public RunnerStats compute(Race race, List<Passage> passages) {
        int completedLoops = passages.size();
        return new RunnerStats(
            completedLoops,
            (long) completedLoops * race.getLoopDistance(),
            (long) completedLoops * race.getLoopElevation(),
            averagePace(race, passages),
            passages.stream().anyMatch(RunnerStatsCalculator::isCorrected)
        );
    }

    /**
     * Temps de boucle d'un passage (RG8) : défini uniquement pour un passage SCAN horodaté.
     */
    public OptionalLong loopTimeMillis(Race race, Passage passage) {
        if (!isTimedScan(passage)) {
            return OptionalLong.empty();
        }
        Duration loopTime = Duration.between(yardCalculator.yardStart(race, passage.getYardNumber()),
            passage.getScannedAt());
        return OptionalLong.of(loopTime.toMillis());
    }

    /**
     * Premier yard non terminé (RG22) : plus grand numéro de yard des passages + 1, ou 1 sans passage.
     */
    public int firstUnfinishedYard(List<Passage> passages) {
        int lastCompletedYard = passages.stream().mapToInt(Passage::getYardNumber).max().orElse(0);
        return lastCompletedYard + 1;
    }

    /** Badge "corrigé" d'un passage (RG10). */
    public static boolean isCorrected(Passage passage) {
        return passage.getSource() == PassageSource.MANUAL;
    }

    private OptionalInt averagePace(Race race, List<Passage> passages) {
        List<Passage> timedScans = passages.stream().filter(RunnerStatsCalculator::isTimedScan).toList();
        if (timedScans.isEmpty()) {
            return OptionalInt.empty();
        }
        long totalMillis = timedScans.stream()
            .mapToLong(passage -> loopTimeMillis(race, passage).orElseThrow())
            .sum();
        long totalMeters = (long) timedScans.size() * race.getLoopDistance();
        BigDecimal secondsPerKm = BigDecimal.valueOf(totalMillis)
            .divide(BigDecimal.valueOf(totalMeters), 0, RoundingMode.HALF_UP);
        return OptionalInt.of(secondsPerKm.intValueExact());
    }

    private static boolean isTimedScan(Passage passage) {
        return passage.getSource() == PassageSource.SCAN && passage.getScannedAt() != null;
    }
}
