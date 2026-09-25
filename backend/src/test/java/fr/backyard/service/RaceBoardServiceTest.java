package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static fr.backyard.testsupport.TestData.RACE_DATE;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.manual;
import static fr.backyard.testsupport.TestData.namedRace;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Spec increment 3 - RG24, RG26, RG27, CA40, CA41, CA43, CA44 (partie [unit]). Repositories mockes, Clock fixe. */
class RaceBoardServiceTest {

    private final FakeRepositories repos = new FakeRepositories();

    private RaceBoardService serviceAt(Instant now) {
        return new RaceBoardService(repos.raceRepository, repos.runnerRepository, repos.passageRepository,
            Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("CA40 - tableau de bord a 10:20 : serverTime, yard 3, fin 11:00, stats par coureur tries par dossard, une seule requete de passages")
    void ca40_boardOfRunningRace() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        Runner b = asDnf(runner(2L, r1, 2, "B", "tok-b"), DnfReason.VOLUNTARY, 2);
        Runner a = runner(1L, r1, 1, "A", "tok-a");
        repos.withRaces(r1).withRunners(b, a).withPassages(
            scan(b, 1, at("08:45:00")),
            scan(a, 1, at("08:45:00")),
            scan(a, 2, at("09:50:00")));

        RaceBoardView board = serviceAt(at("10:20:00")).board(1L);

        assertThat(board.race()).isSameAs(r1);
        assertThat(board.serverTime()).isEqualTo(at("10:20:00"));
        assertThat(board.currentYard()).isEqualTo(3);
        assertThat(board.currentYardEndsAt()).isEqualTo(at("11:00:00"));
        assertThat(board.runners()).extracting(RunnerBoardEntry::runnerId).containsExactly(1L, 2L);
        RunnerBoardEntry entryA = board.runners().get(0);
        assertThat(entryA.bib()).isEqualTo(1);
        assertThat(entryA.name()).isEqualTo("A");
        assertThat(entryA.status()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(entryA.dnfReason()).isNull();
        assertThat(entryA.dnfYard()).isNull();
        assertThat(entryA.completedLoops()).isEqualTo(2);
        assertThat(entryA.distanceMeters()).isEqualTo(13_412L);
        assertThat(entryA.elevationMeters()).isEqualTo(100L);
        assertThat(entryA.averagePaceSecondsPerKm()).hasValue(425);
        assertThat(entryA.corrected()).isFalse();
        RunnerBoardEntry entryB = board.runners().get(1);
        assertThat(entryB.status()).isEqualTo(RunnerStatus.DNF);
        assertThat(entryB.dnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(entryB.dnfYard()).isEqualTo(2);
        assertThat(entryB.completedLoops()).isEqualTo(1);
        assertThat(entryB.distanceMeters()).isEqualTo(6_706L);
        assertThat(entryB.averagePaceSecondsPerKm()).hasValue(403);
        verify(repos.passageRepository, times(1)).findByRunnerRaceId(1L);
        verify(repos.passageRepository, never()).findByRunnerId(any());
        verify(repos.passageRepository, never()).findByRunnerIdAndYardNumber(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("CA41 - tableau de bord d'une course SETUP : yard 0, fin null, coureur sans passage a 0 tour et allure vide")
    void ca41_boardOfSetupRace() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1).withRunners(runner(1L, r1, 1, "A", "tok-a"));

        RaceBoardView board = serviceAt(at("10:20:00")).board(1L);

        assertThat(board.currentYard()).isZero();
        assertThat(board.currentYardEndsAt()).isNull();
        assertThat(board.runners()).singleElement().satisfies(entry -> {
            assertThat(entry.completedLoops()).isZero();
            assertThat(entry.averagePaceSecondsPerKm()).isEmpty();
        });
    }

    @Test
    @DisplayName("CA41 - tableau de bord d'une course FINISHED : yard 0, fin null")
    void ca41_boardOfFinishedRace() {
        Race r1 = backyardTest(RaceStatus.FINISHED);
        repos.withRaces(r1);

        RaceBoardView board = serviceAt(at("10:20:00")).board(1L);

        assertThat(board.currentYard()).isZero();
        assertThat(board.currentYardEndsAt()).isNull();
    }

    @Test
    @DisplayName("CA41 - tableau de bord d'une course inconnue : ResourceNotFoundException")
    void ca41_boardOfUnknownRace() {
        assertThatThrownBy(() -> serviceAt(at("10:20:00")).board(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("CA43 - courses paralleles a 10:10 : R1 yard 3 fin 11:00, R2 yard 2 fin 10:30, chacune ses coureurs")
    void ca43_parallelRaces() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        Race r2 = namedRace(2L, "Backyard Court", RACE_DATE, 6706, 1800, 50, RaceStatus.RUNNING, at("09:30:00"));
        Runner a = runner(1L, r1, 1, "A", "tok-a");
        Runner y = runner(3L, r2, 1, "Y", "tok-y");
        repos.withRaces(r1, r2).withRunners(a, y)
            .withPassages(scan(a, 1, at("08:45:00")), scan(y, 1, at("09:55:00")));
        RaceBoardService service = serviceAt(at("10:10:00"));

        RaceBoardView board1 = service.board(1L);
        RaceBoardView board2 = service.board(2L);

        assertThat(board1.currentYard()).isEqualTo(3);
        assertThat(board1.currentYardEndsAt()).isEqualTo(at("11:00:00"));
        assertThat(board1.runners()).extracting(RunnerBoardEntry::runnerId).containsExactly(1L);
        assertThat(board2.currentYard()).isEqualTo(2);
        assertThat(board2.currentYardEndsAt()).isEqualTo(at("10:30:00"));
        assertThat(board2.runners()).extracting(RunnerBoardEntry::runnerId).containsExactly(3L);
        assertThat(board2.runners().get(0).completedLoops()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA44 - detail d'un coureur : passages tries par yard, temps de boucle et badge corrige derives, stats")
    void ca44_runnerDetail() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        Runner alice = runner(12L, r1, 6, "Alice", "tok-secret");
        repos.withRaces(r1).withRunners(alice)
            .withPassages(manual(alice, 2), scan(alice, 1, at("08:45:00")));

        RunnerDetailView detail = serviceAt(at("10:20:00")).runnerDetail(12L);

        assertThat(detail.runnerId()).isEqualTo(12L);
        assertThat(detail.raceId()).isEqualTo(1L);
        assertThat(detail.bib()).isEqualTo(6);
        assertThat(detail.name()).isEqualTo("Alice");
        assertThat(detail.status()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(detail.passages()).extracting(PassageView::yardNumber).containsExactly(1, 2);
        PassageView yard1 = detail.passages().get(0);
        assertThat(yard1.source()).isEqualTo(PassageSource.SCAN);
        assertThat(yard1.scannedAt()).isEqualTo(at("08:45:00"));
        assertThat(yard1.loopTimeMillis()).hasValue(2_700_000L);
        assertThat(yard1.corrected()).isFalse();
        PassageView yard2 = detail.passages().get(1);
        assertThat(yard2.source()).isEqualTo(PassageSource.MANUAL);
        assertThat(yard2.scannedAt()).isNull();
        assertThat(yard2.loopTimeMillis()).isEmpty();
        assertThat(yard2.corrected()).isTrue();
        assertThat(detail.completedLoops()).isEqualTo(2);
        assertThat(detail.distanceMeters()).isEqualTo(13_412L);
        assertThat(detail.averagePaceSecondsPerKm()).hasValue(403);
        assertThat(detail.corrected()).isTrue();
    }

    @ParameterizedTest(name = "CA44 / RG26 - detail disponible pour une course {0}")
    @EnumSource(RaceStatus.class)
    @DisplayName("CA44 / RG26 - le detail ne depend pas du statut de la course")
    void ca44_runnerDetailIndependentOfRaceStatus(RaceStatus status) {
        Race r1 = backyardTest(status);
        Runner alice = runner(12L, r1, 6, "Alice", "tok-secret");
        repos.withRaces(r1).withRunners(alice);

        RunnerDetailView detail = serviceAt(at("10:20:00")).runnerDetail(12L);

        assertThat(detail.completedLoops()).isZero();
        assertThat(detail.averagePaceSecondsPerKm()).isEmpty();
        assertThat(detail.passages()).isEmpty();
    }

    @Test
    @DisplayName("CA44 - detail d'un coureur inconnu : ResourceNotFoundException")
    void ca44_unknownRunnerDetail() {
        assertThatThrownBy(() -> serviceAt(at("10:20:00")).runnerDetail(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
