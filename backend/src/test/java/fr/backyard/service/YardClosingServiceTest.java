package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.testsupport.FakeRepositories;
import fr.backyard.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.manual;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.race;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.scanInWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Spec increment 2 - RG16 a RG21, CA26 a CA39. Repositories mockes, horloge controlee. */
class YardClosingServiceTest {

    private static final long R1_ID = 101L;
    private static final long R2_ID = 202L;

    private final FakeRepositories repos = new FakeRepositories();
    private final MutableClock clock = new MutableClock(T0);
    private final YardClosingService service = new YardClosingService(
        repos.raceRepository, repos.runnerRepository, repos.passageRepository, clock);

    private final Race r1 = r1(R1_ID);

    /** Coureur ACTIVE de la course, avec un passage SCAN sur chacun des yards 1..lastYard. */
    private Runner givenRunner(Race race, long id, int lastYard) {
        Runner runner = runner(id, race);
        repos.withRaces(race).withRunners(runner);
        for (int yard = 1; yard <= lastYard; yard++) {
            repos.withPassages(scanInWindow(runner, yard));
        }
        return runner;
    }

    private static void assertActive(Runner... runners) {
        for (Runner runner : runners) {
            assertThat(runner.getStatus()).as("statut du coureur %s", runner.getId()).isEqualTo(RunnerStatus.ACTIVE);
            assertThat(runner.getDnfReason()).isNull();
            assertThat(runner.getDnfYard()).isNull();
        }
    }

    private static void assertTimedOut(Runner runner, int dnfYard) {
        assertThat(runner.getStatus()).as("statut du coureur %s", runner.getId()).isEqualTo(RunnerStatus.DNF);
        assertThat(runner.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(runner.getDnfYard()).isEqualTo(dnfYard);
    }

    @Test
    @DisplayName("CA26 - 08:59:59.999 (yard 1) : rien a cloturer, closedYard 0, coureur sans passage reste ACTIVE, aucun save")
    void ca26_nothingToCloseDuringYardOne() {
        Runner a = givenRunner(r1, 1001L, 0);
        clock.set(at("08:59:59.999"));

        YardClosingResult result = service.closeYard(r1);

        assertThat(result.closedYard()).isZero();
        assertThat(result.timedOutRunnerIds()).isEmpty();
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isFalse();
        assertActive(a);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA26 / CL3 - course RUNNING avec now < started_at : rien a cloturer")
    void ca26_nothingToCloseBeforeStart() {
        Runner a = givenRunner(r1, 1001L, 0);
        clock.set(at("07:30:00"));

        YardClosingResult result = service.closeYard(r1);

        assertThat(result.closedYard()).isZero();
        assertActive(a);
        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA27 - 09:00:00.000 : B sans passage yard 1 passe DNF TIMEOUT dnfYard 1, A et C ACTIVE, course RUNNING")
    void ca27_autoDnfAtExactBell() {
        Runner a = givenRunner(r1, 1001L, 1);
        Runner b = givenRunner(r1, 1002L, 0);
        Runner c = givenRunner(r1, 1003L, 1);
        clock.set(at("09:00:00.000"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(b, 1);
        assertActive(a, c);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(result.raceId()).isEqualTo(R1_ID);
        assertThat(result.closedYard()).isEqualTo(1);
        assertThat(result.timedOutRunnerIds()).containsExactly(1002L);
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isFalse();
        assertThat(repos.runnerSaveCount(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA28 - 11:00 (N = 3) : seul le yard 3 est controle, les passages MANUAL comptent comme valides")
    void ca28_onlyLastClosedYardIsCheckedAndManualPassagesAreValid() {
        Runner e = givenRunner(r1, 1005L, 3);
        Runner f = givenRunner(r1, 1006L, 2);
        Runner g = givenRunner(r1, 1007L, 1);
        repos.withPassages(manual(g, 2), manual(g, 3));
        clock.set(at("11:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(f, 3);
        assertActive(e, g);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(result.closedYard()).isEqualTo(3);
        assertThat(result.timedOutRunnerIds()).containsExactly(1006L);
    }

    @Test
    @DisplayName("CA28 / RG16 - un yard anterieur manque (yard 1) n'est pas controle : seul N compte")
    void ca28_earlierMissingYardIsNotChecked() {
        Runner a = givenRunner(r1, 1001L, 0);
        repos.withPassages(scanInWindow(a, 2), scanInWindow(a, 3));
        Runner b = givenRunner(r1, 1002L, 3);
        clock.set(at("11:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertActive(a, b);
        assertThat(result.timedOutRunnerIds()).isEmpty();
    }

    @Test
    @DisplayName("CA29 - 10:00 (N = 2) : un coureur DNF VOLUNTARY sans passage n'est ni modifie ni sauvegarde")
    void ca29_dnfRunnersAreNotTouched() {
        Runner d = asDnf(givenRunner(r1, 1004L, 0), DnfReason.VOLUNTARY, 1);
        Runner a = givenRunner(r1, 1001L, 2);
        Runner b = givenRunner(r1, 1002L, 2);
        clock.set(at("10:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertThat(d.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(d.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(d.getDnfYard()).isEqualTo(1);
        assertThat(repos.runnerSaveCount(d)).isZero();
        assertActive(a, b);
        assertThat(result.timedOutRunnerIds()).isEmpty();
    }

    @Test
    @DisplayName("CA29 / RG19 - un coureur WINNER sans passage sur N n'est ni modifie ni sauvegarde")
    void ca29_winnerRunnersAreNotTouched() {
        Runner w = givenRunner(r1, 1009L, 1);
        w.setStatus(RunnerStatus.WINNER);
        Runner a = givenRunner(r1, 1001L, 2);
        Runner b = givenRunner(r1, 1002L, 2);
        clock.set(at("10:00:00"));

        service.closeYard(r1);

        assertThat(w.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(w.getDnfReason()).isNull();
        assertThat(w.getDnfYard()).isNull();
        assertThat(repos.runnerSaveCount(w)).isZero();
        assertActive(a, b);
    }

    @Test
    @DisplayName("CA30 - deux clotures dans le meme yard (09:00:00 puis 09:00:05) : etat identique, B sauvegarde une seule fois")
    void ca30_closingTwiceIsIdempotent() {
        Runner a = givenRunner(r1, 1001L, 1);
        Runner b = givenRunner(r1, 1002L, 0);
        Runner c = givenRunner(r1, 1003L, 1);

        clock.set(at("09:00:00"));
        service.closeYard(r1);
        clock.set(at("09:00:05"));
        YardClosingResult second = service.closeYard(r1);

        assertTimedOut(b, 1);
        assertActive(a, c);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(second.closedYard()).isEqualTo(1);
        assertThat(second.timedOutRunnerIds()).isEmpty();
        assertThat(second.winnerRunnerId()).isEmpty();
        assertThat(second.raceFinished()).isFalse();
        assertThat(repos.runnerSaveCount(b)).isEqualTo(1);
    }

    /**
     * CA31 : un second finisher V (passage yard 1) est ajoute a R2. Sans lui, Y serait l'unique
     * finisher ACTIVE du yard 1 de R2 et RG21 le designerait WINNER, en contradiction avec
     * l'attendu "Y : ACTIVE" de la spec (ecart signale).
     */
    @Test
    @DisplayName("CA31 - courses en parallele a 10:00 : chaque course RUNNING cloture son propre yard, SETUP et FINISHED jamais traitees")
    void ca31_parallelRacesAreClosedIndependently() {
        Race r2 = race(R2_ID, 6706, 1800, 50, RaceStatus.RUNNING, at("09:30:00"));
        Race r3 = race(303L, 6706, 3600, 50, RaceStatus.SETUP, null);
        Race r4 = race(404L, 6706, 3600, 50, RaceStatus.FINISHED, T0);
        Runner x = givenRunner(r1, 1011L, 1);
        Runner y = runner(2001L, r2);
        Runner v = runner(2003L, r2);
        Runner z = runner(2002L, r2);
        Runner w = runner(3001L, r3);
        repos.withRaces(r2, r3, r4).withRunners(y, v, z, w)
            .withPassages(scan(y, 1, at("09:55:00")), scan(v, 1, at("09:56:00")));
        clock.set(at("10:00:00"));

        List<YardClosingResult> results = service.closeElapsedYards();

        assertTimedOut(x, 2);
        assertActive(y, v);
        assertTimedOut(z, 1);
        assertActive(w);
        assertThat(repos.runnerSaveCount(w)).isZero();
        assertThat(r3.getStatus()).isEqualTo(RaceStatus.SETUP);
        assertThat(r4.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(r1.getStatus()).as("R1 sans finisher du yard 2 : FINISHED sans vainqueur")
            .isEqualTo(RaceStatus.FINISHED);
        assertThat(r2.getStatus()).as("R2 avec deux finishers : reste RUNNING").isEqualTo(RaceStatus.RUNNING);
        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.raceId().equals(R1_ID))
            .singleElement().satisfies(r -> {
                assertThat(r.closedYard()).isEqualTo(2);
                assertThat(r.timedOutRunnerIds()).containsExactly(1011L);
                assertThat(r.winnerRunnerId()).isEmpty();
                assertThat(r.raceFinished()).isTrue();
            });
        assertThat(results).filteredOn(r -> r.raceId().equals(R2_ID))
            .singleElement().satisfies(r -> {
                assertThat(r.closedYard()).isEqualTo(1);
                assertThat(r.timedOutRunnerIds()).containsExactly(2002L);
                assertThat(r.winnerRunnerId()).isEmpty();
                assertThat(r.raceFinished()).isFalse();
            });
        verify(repos.raceRepository).findByStatus(RaceStatus.RUNNING);
        verify(repos.raceRepository, never()).findByStatus(RaceStatus.SETUP);
        verify(repos.raceRepository, never()).findByStatus(RaceStatus.FINISHED);
        verify(repos.runnerRepository, never()).findByRaceId(303L);
        verify(repos.runnerRepository, never()).findByRaceId(404L);
        verify(repos.runnerRepository, never()).findByRaceIdAndStatus(eq(303L), any());
        verify(repos.runnerRepository, never()).findByRaceIdAndStatus(eq(404L), any());
    }

    @Test
    @DisplayName("CA32 - une erreur sur R1 n'empeche pas le traitement de R2 ; exception finale citant R1 et portant la cause d'origine")
    void ca32_failureOnOneRaceDoesNotBlockOthers() {
        Race r2 = race(R2_ID, 6706, 1800, 50, RaceStatus.RUNNING, at("09:30:00"));
        givenRunner(r1, 1011L, 1);
        Runner y = runner(2001L, r2);
        Runner v = runner(2003L, r2);
        Runner z = runner(2002L, r2);
        repos.withRaces(r2).withRunners(y, v, z)
            .withPassages(scan(y, 1, at("09:55:00")), scan(v, 1, at("09:56:00")));
        RuntimeException original = new IllegalStateException("panne base de donnees simulee");
        doThrow(original).when(repos.runnerRepository).findByRaceId(R1_ID);
        doThrow(original).when(repos.runnerRepository).findByRaceIdAndStatus(eq(R1_ID), any());
        clock.set(at("10:00:00"));

        Throwable thrown = catchThrowable(service::closeElapsedYards);

        assertTimedOut(z, 1);
        assertActive(y, v);
        assertThat(r2.getStatus()).as("R2 entierement traitee, deux finishers : reste RUNNING")
            .isEqualTo(RaceStatus.RUNNING);
        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown).isNotSameAs(original);
        assertThat(mentionsNumber(thrown.getMessage(), R1_ID))
            .as("message '%s' contient l'id de R1", thrown.getMessage()).isTrue();
        assertThat(carries(thrown, original))
            .as("l'exception porte l'exception d'origine (cause ou supprimee)").isTrue();
    }

    private static boolean carries(Throwable thrown, Throwable original) {
        if (thrown == null) {
            return false;
        }
        if (thrown == original) {
            return true;
        }
        return carries(thrown.getCause(), original)
            || Arrays.stream(thrown.getSuppressed()).anyMatch(s -> carries(s, original));
    }

    @Test
    @DisplayName("CA33 - 13:00 (N = 5) : B DNF dnfYard 5, A unique finisher ACTIVE devient WINNER, course FINISHED")
    void ca33_singleActiveFinisherWins() {
        Runner a = givenRunner(r1, 1001L, 5);
        Runner b = givenRunner(r1, 1002L, 4);
        clock.set(at("13:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(b, 5);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(a.getDnfReason()).isNull();
        assertThat(a.getDnfYard()).isNull();
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(result.closedYard()).isEqualTo(5);
        assertThat(result.timedOutRunnerIds()).containsExactly(1002L);
        assertThat(result.winnerRunnerId()).contains(1001L);
        assertThat(result.raceFinished()).isTrue();
    }

    @Test
    @DisplayName("CA34 - au moins deux finishers : C DNF dnfYard 5, A et B ACTIVE, course RUNNING, pas de vainqueur")
    void ca34_twoFinishersRaceGoesOn() {
        Runner a = givenRunner(r1, 1001L, 5);
        Runner b = givenRunner(r1, 1002L, 5);
        Runner c = givenRunner(r1, 1003L, 4);
        clock.set(at("13:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(c, 5);
        assertActive(a, b);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isFalse();
    }

    @Test
    @DisplayName("CA35 - aucun finisher : A et B DNF TIMEOUT dnfYard 5, course FINISHED sans vainqueur")
    void ca35_noFinisherRaceEndsWithoutWinner() {
        Runner a = givenRunner(r1, 1001L, 4);
        Runner b = givenRunner(r1, 1002L, 4);
        clock.set(at("13:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(a, 5);
        assertTimedOut(b, 5);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(result.timedOutRunnerIds()).containsExactlyInAnyOrder(1001L, 1002L);
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isTrue();
    }

    @Test
    @DisplayName("CA36 - le dernier actif doit courir seul un yard de plus : RUNNING a N = 5, WINNER a N = 6")
    void ca36_lastActiveRunnerMustCompleteOneMoreYardAlone() {
        Runner a = givenRunner(r1, 1001L, 5);
        Runner b = asDnf(givenRunner(r1, 1002L, 5), DnfReason.VOLUNTARY, 6);

        clock.set(at("13:00:00"));
        YardClosingResult first = service.closeYard(r1);

        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertActive(a);
        assertThat(first.winnerRunnerId()).isEmpty();
        assertThat(first.raceFinished()).isFalse();

        repos.withPassages(scanInWindow(a, 6));
        clock.set(at("14:00:00"));
        YardClosingResult second = service.closeYard(r1);

        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(second.winnerRunnerId()).contains(1001L);
        assertThat(second.raceFinished()).isTrue();
        assertThat(b.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(b.getDnfYard()).isEqualTo(6);
    }

    @Test
    @DisplayName("CA37 - unique finisher non ACTIVE (DNF VOLUNTARY) : B DNF dnfYard 5, course FINISHED sans vainqueur")
    void ca37_singleNonActiveFinisherEndsRaceWithoutWinner() {
        Runner a = asDnf(givenRunner(r1, 1001L, 5), DnfReason.VOLUNTARY, 6);
        Runner b = givenRunner(r1, 1002L, 4);
        clock.set(at("13:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertTimedOut(b, 5);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(a.getDnfYard()).isEqualTo(6);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isTrue();
    }

    @Test
    @DisplayName("CA38 - une course passee FINISHED (CA33) n'est plus retraitee par closeElapsedYards")
    void ca38_finishedRaceIsNotProcessedAgain() {
        Runner a = givenRunner(r1, 1001L, 5);
        Runner b = givenRunner(r1, 1002L, 4);
        clock.set(at("13:00:00"));
        service.closeElapsedYards();
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        repos.clearWriteLog();

        clock.set(at("13:00:10"));
        List<YardClosingResult> results = service.closeElapsedYards();

        assertThat(results).isEmpty();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertTimedOut(b, 5);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA39 - course a un seul coureur ayant termine le yard 1 : WINNER des 09:00, course FINISHED")
    void ca39_singleRunnerRaceWinsAtFirstClosing() {
        Runner a = givenRunner(r1, 1001L, 1);
        clock.set(at("09:00:00"));

        YardClosingResult result = service.closeYard(r1);

        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(result.winnerRunnerId()).contains(1001L);
        assertThat(result.raceFinished()).isTrue();
    }
}
