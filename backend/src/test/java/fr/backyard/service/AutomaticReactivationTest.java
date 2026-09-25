package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.testsupport.FakeRepositories;
import fr.backyard.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.asWinner;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.scanInWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec increment 2 - RG29 a RG32 (reactivation automatique par scan recu apres la cloture),
 * CA52 a CA58, CA60, CA62. Repositories mockes, horloge controlee.
 */
class AutomaticReactivationTest {

    private static final long R1_ID = 101L;
    private static final long A_ID = 1001L;
    private static final long B_ID = 1002L;
    private static final long C_ID = 1003L;

    private final FakeRepositories repos = new FakeRepositories();
    private final MutableClock clock = new MutableClock(T0);
    private final PassageRecordingService recording =
        new PassageRecordingService(repos.runnerRepository, repos.passageRepository, clock);
    private final YardClosingService closing = new YardClosingService(
        repos.raceRepository, repos.runnerRepository, repos.passageRepository, clock);
    private final ManualDnfService manualDnf =
        new ManualDnfService(repos.runnerRepository, repos.passageRepository, clock);

    private final Race r1 = r1(R1_ID);
    private Runner a;
    private Runner b;
    private Runner c;

    private Runner givenRunner(long id, String qrToken, int lastYard) {
        Runner runner = runner(id, r1, qrToken);
        repos.withRaces(r1).withRunners(runner);
        for (int yard = 1; yard <= lastYard; yard++) {
            repos.withPassages(scanInWindow(runner, yard));
        }
        return runner;
    }

    /**
     * Situation S : A et C ACTIVE avec SCAN 1, 2, 3 ; B ("tok-b") SCAN 1 a 08:45, 2 a 09:50,
     * aucun passage yard 3, DNF TIMEOUT dnfYard 3 (cloture de 11:00, course restee RUNNING).
     */
    private void givenSituationS() {
        a = givenRunner(A_ID, "tok-a", 3);
        c = givenRunner(C_ID, "tok-c", 3);
        b = runner(B_ID, r1, "tok-b");
        repos.withRunners(b).withPassages(scan(b, 1, at("08:45:00")), scan(b, 2, at("09:50:00")));
        asDnf(b, DnfReason.TIMEOUT, 3);
    }

    /** Etat final de CA52 : B reactive par un scan yard 3 a 10:59:30 recu a 11:02. */
    private Passage givenFinalStateOfCa52() {
        givenSituationS();
        clock.set(at("11:02:00"));
        Passage passage = recording.recordScan("tok-b", at("10:59:30"));
        repos.clearWriteLog();
        return passage;
    }

    private void assertStillTimedOutOnYard3() {
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(b.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(b.getDnfYard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA52 - reactivation nominale : scan yard 3 (10:59:30) recu a 11:02 -> passage SCAN yard 3, B ACTIVE et sauvegarde, A, C, R1 inchanges")
    void ca52_nominalReactivation() {
        givenSituationS();
        clock.set(at("11:02:00"));

        Passage passage = recording.recordScan("tok-b", at("10:59:30"));

        assertThat(passage.getYardNumber()).isEqualTo(3);
        assertThat(passage.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(passage.getScannedAt()).isEqualTo(at("10:59:30"));
        assertThat(passage.getRunner()).isSameAs(b);
        assertThat(repos.savedPassages()).containsExactly(passage);
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(b.getDnfReason()).isNull();
        assertThat(b.getDnfYard()).isNull();
        assertThat(repos.runnerSaveCount(b)).isGreaterThanOrEqualTo(1);
        assertThat(repos.savedRunners()).allMatch(r -> r == b);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(c.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(repos.savedRaces()).isEmpty();
    }

    @ParameterizedTest(name = "CA53 - dnfReason {0}")
    @EnumSource(value = DnfReason.class, names = {"VOLUNTARY", "MANUAL", "OTHER"})
    @DisplayName("CA53 - pas de reactivation d'un DNF non-timeout : BusinessConflictException (id, raison, 3), aucun save, DNF inchange")
    void ca53_noReactivationOfNonTimeoutDnf(DnfReason reason) {
        givenSituationS();
        asDnf(b, reason, 3);
        clock.set(at("11:02:00"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("10:59:30")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), B_ID))
                    .as("message '%s' contient l'id de B", e.getMessage()).isTrue();
                assertThat(e.getMessage()).contains(reason.name());
                assertThat(mentionsNumber(e.getMessage(), 3))
                    .as("message '%s' contient le yard 3", e.getMessage()).isTrue();
            });

        repos.assertNoWrite();
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(b.getDnfReason()).isEqualTo(reason);
        assertThat(b.getDnfYard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA54 - pas de reactivation si le scan porte sur le yard 4 alors que dnfYard = 3 : conflit citant 3 et 4, aucun save")
    void ca54_noReactivationWhenScanIsNotOnDnfYard() {
        givenSituationS();
        clock.set(at("11:10:00"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("11:05:00")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), 3))
                    .as("message '%s' contient dnfYard 3", e.getMessage()).isTrue();
                assertThat(mentionsNumber(e.getMessage(), 4))
                    .as("message '%s' contient le yard du scan 4", e.getMessage()).isTrue();
            });

        repos.assertNoWrite();
        assertStillTimedOutOnYard3();
    }

    @Test
    @DisplayName("CA55 - scan recu apres la fin de course : conflit citant R1, FINISHED et 5 ; vainqueur, statuts et course inchanges")
    void ca55_scanAfterRaceFinishedIsRejected() {
        a = asWinner(givenRunner(A_ID, "tok-a", 5));
        b = asDnf(givenRunner(B_ID, "tok-b", 4), DnfReason.TIMEOUT, 5);
        r1.setStatus(RaceStatus.FINISHED);
        clock.set(at("13:00:20"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("12:59:58")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), R1_ID))
                    .as("message '%s' contient l'id de R1", e.getMessage()).isTrue();
                assertThat(e.getMessage()).contains("FINISHED");
                assertThat(mentionsNumber(e.getMessage(), 5))
                    .as("message '%s' contient le yard 5", e.getMessage()).isTrue();
            });

        repos.assertNoWrite();
        assertThat(repos.savedRaces()).isEmpty();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(b.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(b.getDnfYard()).isEqualTo(5);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
    }

    @Test
    @DisplayName("CA56 - yard N+1 deja clos : scan yard 3 recu a 12:00:10 (yard 5) rejete, message citant 3, 5 et reintegration")
    void ca56_noReactivationWhenNextYardIsAlsoClosed() {
        givenSituationS();
        repos.withPassages(scanInWindow(a, 4), scanInWindow(c, 4));
        clock.set(at("12:00:10"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("10:59:30")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), 3))
                    .as("message '%s' contient le yard du scan 3", e.getMessage()).isTrue();
                assertThat(mentionsNumber(e.getMessage(), 5))
                    .as("message '%s' contient le yard courant 5", e.getMessage()).isTrue();
                assertThat(e.getMessage()).containsIgnoringCase("réintégration");
            });

        repos.assertNoWrite();
        assertStillTimedOutOnYard3();
    }

    @Test
    @DisplayName("CA57 - retry identique apres reactivation : passage existant renvoye, aucun save, B ACTIVE")
    void ca57_identicalRetryAfterReactivationIsIdempotent() {
        Passage first = givenFinalStateOfCa52();
        clock.set(at("11:03:00"));

        Passage retried = recording.recordScan("tok-b", at("10:59:30"));

        assertThat(retried).isSameAs(first);
        repos.assertNoWrite();
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA57 - second scan different sur le yard 3 apres reactivation : BusinessConflictException, aucun save, B ACTIVE")
    void ca57_differentScanAfterReactivationIsRejected() {
        givenFinalStateOfCa52();
        clock.set(at("11:03:00"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("10:59:40")))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA58 - clotures apres reactivation : B ACTIVE a 11:05 (N = 3), puis DNF TIMEOUT dnfYard 4 a 12:00 sans passage yard 4")
    void ca58_closingsAfterReactivation() {
        givenFinalStateOfCa52();

        clock.set(at("11:05:00"));
        YardClosingResult reclose = closing.closeYard(r1);

        assertThat(reclose.closedYard()).isEqualTo(3);
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(reclose.timedOutRunnerIds()).isEmpty();
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);

        repos.withPassages(scanInWindow(a, 4), scanInWindow(c, 4));
        clock.set(at("12:00:00"));
        YardClosingResult next = closing.closeYard(r1);

        assertThat(b.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(b.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(b.getDnfYard()).isEqualTo(4);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(c.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(next.timedOutRunnerIds()).containsExactly(B_ID);
    }

    @Test
    @DisplayName("CA60 - regle k-1 en mode reactivation : B DNF TIMEOUT dnfYard 3 sans passage yard 2 -> conflit citant le yard 2, aucun save")
    void ca60_previousYardRuleAppliesInReactivationMode() {
        b = asDnf(givenRunner(B_ID, "tok-b", 1), DnfReason.TIMEOUT, 3);
        clock.set(at("11:02:00"));

        assertThatThrownBy(() -> recording.recordScan("tok-b", at("10:59:30")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 2))
                .as("message '%s' mentionne le yard 2", e.getMessage()).isTrue());

        repos.assertNoWrite();
        assertStillTimedOutOnYard3();
    }

    @Test
    @DisplayName("CA62 - coureur reactive pris en compte a la cloture suivante : C DNF dnfYard 4, A VOLUNTARY, B WINNER, R1 FINISHED")
    void ca62_reactivatedRunnerCountsAtNextClosing() {
        givenFinalStateOfCa52();
        clock.set(at("11:30:00"));
        manualDnf.declareDnf(A_ID, DnfReason.VOLUNTARY);
        assertThat(a.getDnfYard()).isEqualTo(4);
        clock.set(at("11:46:00"));
        recording.recordScan("tok-b", at("11:45:00"));

        clock.set(at("12:00:00"));
        YardClosingResult result = closing.closeYard(r1);

        assertThat(c.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(c.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(c.getDnfYard()).isEqualTo(4);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(a.getDnfYard()).isEqualTo(4);
        assertThat(b.getStatus()).isEqualTo(RunnerStatus.WINNER);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.FINISHED);
        assertThat(result.winnerRunnerId()).contains(B_ID);
        assertThat(result.raceFinished()).isTrue();
        assertThat(result.timedOutRunnerIds()).containsExactly(C_ID);
    }
}
