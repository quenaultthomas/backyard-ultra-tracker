package fr.backyard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.manual;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.race;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec increment 2 - RG5 a RG11, CA9 a CA16, CA50, CA59. Aucun "now", aucun statut. */
class RunnerStatsCalculatorTest {

    private final RunnerStatsCalculator calculator = new RunnerStatsCalculator();
    private final Race r1 = r1(101L);
    private final Runner runner = runner(1001L, r1);

    @Test
    @DisplayName("CA9 - coureur sans passage : 0 tour, 0 m, 0 m D+, allure vide, non corrige")
    void ca9_noPassage() {
        RunnerStats stats = calculator.compute(r1, List.of());

        assertThat(stats.completedLoops()).isZero();
        assertThat(stats.distanceMeters()).isZero();
        assertThat(stats.elevationMeters()).isZero();
        assertThat(stats.averagePaceSecondsPerKm()).isEmpty();
        assertThat(stats.corrected()).isFalse();
    }

    @Test
    @DisplayName("CA10 - trois scans : temps de boucle 2 700 000 / 3 000 000 / 3 300 000 ms")
    void ca10_loopTimes() {
        Passage p1 = scan(runner, 1, at("08:45:00"));
        Passage p2 = scan(runner, 2, at("09:50:00"));
        Passage p3 = scan(runner, 3, at("10:55:00"));

        assertThat(calculator.loopTimeMillis(r1, p1)).hasValue(2_700_000L);
        assertThat(calculator.loopTimeMillis(r1, p2)).hasValue(3_000_000L);
        assertThat(calculator.loopTimeMillis(r1, p3)).hasValue(3_300_000L);
    }

    @Test
    @DisplayName("CA10 - trois scans : 3 tours, 20118 m, 150 m D+, allure 447 s/km, non corrige")
    void ca10_threeScans() {
        List<Passage> passages = List.of(
            scan(runner, 1, at("08:45:00")),
            scan(runner, 2, at("09:50:00")),
            scan(runner, 3, at("10:55:00")));

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.completedLoops()).isEqualTo(3);
        assertThat(stats.distanceMeters()).isEqualTo(20_118L);
        assertThat(stats.elevationMeters()).isEqualTo(150L);
        assertThat(stats.averagePaceSecondsPerKm()).hasValue(447);
        assertThat(stats.corrected()).isFalse();
    }

    @Test
    @DisplayName("CA11 - arrondi HALF_UP : 2 702 500 ms / 5000 m = 540,5 -> 541 s/km")
    void ca11_paceRoundsHalfUp() {
        Race flat5k = race(105L, 5000, 3600, 0, RaceStatus.RUNNING, T0);
        Runner r = runner(1005L, flat5k);

        RunnerStats stats = calculator.compute(flat5k, List.of(scan(r, 1, at("08:45:02.500"))));

        assertThat(stats.averagePaceSecondsPerKm()).hasValue(541);
    }

    @Test
    @DisplayName("CA11 - arrondi HALF_UP : 2 700 000 ms / 6706 m = 402,62 -> 403 s/km")
    void ca11_paceRoundsDownBelowHalf() {
        RunnerStats stats = calculator.compute(r1, List.of(scan(runner, 1, at("08:45:00"))));

        assertThat(stats.averagePaceSecondsPerKm()).hasValue(403);
    }

    @Test
    @DisplayName("CA12 - scans et passages MANUAL : 4 tours, 26824 m, 200 m D+, allure 425 sur les seuls SCAN, corrige")
    void ca12_mixedScanAndManual() {
        Passage manualYard2 = manual(runner, 2);
        List<Passage> passages = List.of(
            scan(runner, 1, at("08:45:00")),
            manualYard2,
            manual(runner, 3),
            scan(runner, 4, at("11:50:00")));

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.completedLoops()).isEqualTo(4);
        assertThat(stats.distanceMeters()).isEqualTo(26_824L);
        assertThat(stats.elevationMeters()).isEqualTo(200L);
        assertThat(stats.averagePaceSecondsPerKm()).hasValue(425);
        assertThat(stats.corrected()).isTrue();
        assertThat(calculator.loopTimeMillis(r1, manualYard2)).isEmpty();
    }

    @Test
    @DisplayName("CA13 - uniquement des passages MANUAL : 2 tours, allure vide (jamais 0), corrige")
    void ca13_onlyManualPassages() {
        RunnerStats stats = calculator.compute(r1, List.of(manual(runner, 1), manual(runner, 2)));

        assertThat(stats.completedLoops()).isEqualTo(2);
        assertThat(stats.averagePaceSecondsPerKm()).isEmpty();
        assertThat(stats.corrected()).isTrue();
    }

    @Test
    @DisplayName("CA14 - un passage MANUAL avec scannedAt renseigne est exclu de l'allure et n'a pas de temps de boucle")
    void ca14_manualWithScannedAtExcludedFromPace() {
        Passage manualWithTime = new Passage(runner, 2, PassageSource.MANUAL, at("09:10:00"));
        List<Passage> passages = List.of(scan(runner, 1, at("08:45:00")), manualWithTime);

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.averagePaceSecondsPerKm()).hasValue(403);
        assertThat(calculator.loopTimeMillis(r1, manualWithTime)).isEmpty();
    }

    @Test
    @DisplayName("CA15 - course plate (loopElevation = 0), 5 passages : 0 m D+, 5 tours")
    void ca15_flatCourse() {
        Race flat = race(106L, 6706, 3600, 0, RaceStatus.RUNNING, T0);
        Runner r = runner(1006L, flat);
        List<Passage> passages = List.of(
            scan(r, 1, at("08:50:00")),
            scan(r, 2, at("09:50:00")),
            scan(r, 3, at("10:50:00")),
            scan(r, 4, at("11:50:00")),
            scan(r, 5, at("12:50:00")));

        RunnerStats stats = calculator.compute(flat, passages);

        assertThat(stats.elevationMeters()).isZero();
        assertThat(stats.completedLoops()).isEqualTo(5);
    }

    @Test
    @DisplayName("CA16 - independance du statut : coureur DNF TIMEOUT dnfYard 4 avec 3 scans -> 3 tours, 20118 m")
    void ca16_statsDoNotDependOnRunnerStatus() {
        Runner dnfRunner = asDnf(runner(1007L, r1), DnfReason.TIMEOUT, 4);
        List<Passage> passages = List.of(
            scan(dnfRunner, 1, at("08:45:00")),
            scan(dnfRunner, 2, at("09:50:00")),
            scan(dnfRunner, 3, at("10:55:00")));

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.completedLoops()).isEqualTo(3);
        assertThat(stats.distanceMeters()).isEqualTo(20_118L);
    }

    @Test
    @DisplayName("CA16 / RG11 - les statistiques ne dependent pas du statut de la course (FINISHED)")
    void ca16_statsDoNotDependOnRaceStatus() {
        Race finished = race(107L, 6706, 3600, 50, RaceStatus.FINISHED, T0);
        Runner r = runner(1008L, finished);

        RunnerStats stats = calculator.compute(finished, List.of(scan(r, 1, at("08:45:00"))));

        assertThat(stats.completedLoops()).isEqualTo(1);
        assertThat(stats.averagePaceSecondsPerKm()).hasValue(403);
    }

    @Test
    @DisplayName("CA50 - statistiques apres reintegration (SCAN 1, 2 et MANUAL 3, 4) : 4 tours, 26824 m, 200 m D+, allure 425, corrige")
    void ca50_statsAfterReintegration() {
        List<Passage> passages = List.of(
            scan(runner, 1, at("08:45:00")),
            scan(runner, 2, at("09:50:00")),
            manual(runner, 3),
            manual(runner, 4));

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.completedLoops()).isEqualTo(4);
        assertThat(stats.distanceMeters()).isEqualTo(26_824L);
        assertThat(stats.elevationMeters()).isEqualTo(200L);
        assertThat(stats.averagePaceSecondsPerKm()).hasValue(425);
        assertThat(stats.corrected()).isTrue();
    }

    @Test
    @DisplayName("CA59 - statistiques apres reactivation automatique (3 SCAN) : 3 tours, 20118 m, 150 m D+, allure 461, non corrige")
    void ca59_statsAfterAutomaticReactivation() {
        List<Passage> passages = List.of(
            scan(runner, 1, at("08:45:00")),
            scan(runner, 2, at("09:50:00")),
            scan(runner, 3, at("10:59:30")));

        RunnerStats stats = calculator.compute(r1, passages);

        assertThat(stats.completedLoops()).isEqualTo(3);
        assertThat(stats.distanceMeters()).isEqualTo(20_118L);
        assertThat(stats.elevationMeters()).isEqualTo(150L);
        assertThat(stats.averagePaceSecondsPerKm()).hasValue(461);
        assertThat(stats.corrected()).isFalse();
    }
}
