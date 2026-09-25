package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.FakeRepositories;
import fr.backyard.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.asWinner;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.race;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.scanInWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec increment 2 - RG23 a RG27, CA44 a CA49, CA51. Repositories mockes, horloge controlee. */
class ReintegrationServiceTest {

    private static final long B_ID = 1002L;

    private final FakeRepositories repos = new FakeRepositories();
    private final MutableClock clock = new MutableClock(T0);
    private final ReintegrationService service =
        new ReintegrationService(repos.runnerRepository, repos.passageRepository, clock);

    private final Race r1 = r1(101L);

    private Runner givenRunner(Race race, long id, int lastYard) {
        Runner runner = runner(id, race);
        repos.withRaces(race).withRunners(runner);
        for (int yard = 1; yard <= lastYard; yard++) {
            repos.withPassages(scanInWindow(runner, yard));
        }
        return runner;
    }

    private static void assertReactivated(Runner runner) {
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(runner.getDnfReason()).isNull();
        assertThat(runner.getDnfYard()).isNull();
    }

    /** B de CA44 : SCAN yard 1 a 08:45, yard 2 a 09:50, DNF TIMEOUT dnfYard 3. */
    private Runner givenRunnerBOfCa44() {
        Runner b = runner(B_ID, r1, "tok-b");
        repos.withRaces(r1).withRunners(b)
            .withPassages(scan(b, 1, at("08:45:00")), scan(b, 2, at("09:50:00")));
        return asDnf(b, DnfReason.TIMEOUT, 3);
    }

    @Test
    @DisplayName("CA44 - reintegration nominale a 12:15 (yard 5) : passages MANUAL yards 3 et 4 crees dans l'ordre, B ACTIVE, yards 1 et 2 intacts")
    void ca44_nominalReintegration() {
        Runner b = givenRunnerBOfCa44();
        List<Passage> before = repos.passagesOf(b);
        clock.set(at("12:15:00"));

        List<Passage> created = service.reintegrate(B_ID);

        assertThat(created).extracting(Passage::getYardNumber).containsExactly(3, 4);
        assertThat(created).allSatisfy(p -> {
            assertThat(p.getSource()).isEqualTo(PassageSource.MANUAL);
            assertThat(p.getScannedAt()).isNull();
            assertThat(p.getRunner()).isSameAs(b);
        });
        assertThat(repos.savedPassages()).containsExactlyInAnyOrderElementsOf(created);
        assertReactivated(b);
        assertThat(repos.passagesOf(b)).containsAll(before).hasSize(4);
        assertThat(before.get(0).getScannedAt()).isEqualTo(at("08:45:00"));
        assertThat(before.get(0).getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(before.get(1).getScannedAt()).isEqualTo(at("09:50:00"));
        assertThat(before.get(1).getSource()).isEqualTo(PassageSource.SCAN);
        repos.assertNoPassageDeletion();
    }

    @Test
    @DisplayName("CA45 - reintegration dans le yard qui suit le DNF (11:10, yard 4) : un seul passage MANUAL yard 3")
    void ca45_reintegrationRightAfterDnf() {
        Runner b = givenRunnerBOfCa44();
        clock.set(at("11:10:00"));

        List<Passage> created = service.reintegrate(B_ID);

        assertThat(created).singleElement().satisfies(p -> {
            assertThat(p.getYardNumber()).isEqualTo(3);
            assertThat(p.getSource()).isEqualTo(PassageSource.MANUAL);
            assertThat(p.getScannedAt()).isNull();
        });
        assertReactivated(b);
    }

    @Test
    @DisplayName("CA46 - intervalle vide (DNF VOLUNTARY dnfYard 3, horloge 10:30 yard 3) : aucun passage, liste vide, ACTIVE")
    void ca46_emptyInterval() {
        Runner a = asDnf(givenRunner(r1, 1001L, 2), DnfReason.VOLUNTARY, 3);
        clock.set(at("10:30:00"));

        List<Passage> created = service.reintegrate(1001L);

        assertThat(created).isEmpty();
        assertThat(repos.savedPassages()).isEmpty();
        assertReactivated(a);
    }

    @Test
    @DisplayName("CA47 - passage deja present sur dnfYard (concurrence) : non recree ni modifie, C ACTIVE")
    void ca47_existingPassageIsNotRecreated() {
        Runner c = asDnf(givenRunner(r1, 1003L, 2), DnfReason.TIMEOUT, 2);
        Passage existingYard2 = repos.passageOf(c, 2).orElseThrow();
        clock.set(at("10:30:00"));

        List<Passage> created = service.reintegrate(1003L);

        assertThat(created).isEmpty();
        assertThat(repos.savedPassages()).isEmpty();
        assertThat(repos.passagesOf(c)).hasSize(2).contains(existingYard2);
        assertThat(existingYard2.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(existingYard2.getScannedAt()).isNotNull();
        assertReactivated(c);
        repos.assertNoPassageDeletion();
    }

    /**
     * CA48 : deux coureurs A et C (passages 1 a 4, puis 5) accompagnent B. Sans eux, B serait l'unique
     * finisher ACTIVE du yard 4 et RG21 le designerait WINNER a 12:20, ce qui rendrait la suite
     * de CA48 impossible (ecart signale).
     */
    @Test
    @DisplayName("CA48 - pas de re-DNF sur les yards recrees : ACTIVE a la cloture de 12:20 (N = 4), DNF TIMEOUT dnfYard 5 a 13:00")
    void ca48_noReDnfOnRecreatedYards() {
        Runner b = givenRunnerBOfCa44();
        Runner a = givenRunner(r1, 1001L, 4);
        Runner c = givenRunner(r1, 1003L, 4);
        YardClosingService closing = new YardClosingService(
            repos.raceRepository, repos.runnerRepository, repos.passageRepository, clock);
        clock.set(at("12:15:00"));
        service.reintegrate(B_ID);

        clock.set(at("12:20:00"));
        YardClosingResult atYard5 = closing.closeYard(r1);

        assertThat(atYard5.closedYard()).isEqualTo(4);
        assertReactivated(b);
        assertThat(atYard5.timedOutRunnerIds()).doesNotContain(B_ID);

        repos.withPassages(scanInWindow(a, 5), scanInWindow(c, 5));
        clock.set(at("13:00:00"));
        YardClosingResult atYard6 = closing.closeYard(r1);

        assertThat(b.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(b.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(b.getDnfYard()).isEqualTo(5);
        assertThat(atYard6.timedOutRunnerIds()).containsExactly(B_ID);
    }

    @Test
    @DisplayName("CA49 - scan apres reintegration (12:50, horloge 12:55) : passage SCAN yard 5 enregistre")
    void ca49_scanAfterReintegration() {
        Runner b = givenRunnerBOfCa44();
        PassageRecordingService recording =
            new PassageRecordingService(repos.runnerRepository, repos.passageRepository, clock);
        clock.set(at("12:15:00"));
        service.reintegrate(B_ID);

        clock.set(at("12:55:00"));
        Passage passage = recording.recordScan("tok-b", at("12:50:00"));

        assertThat(passage.getYardNumber()).isEqualTo(5);
        assertThat(passage.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(passage.getScannedAt()).isEqualTo(at("12:50:00"));
        assertThat(repos.passagesOf(b)).extracting(Passage::getYardNumber).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("CA51 - coureur ACTIVE : BusinessConflictException (id et statut), aucun save")
    void ca51_activeRunnerIsRejected() {
        givenRunner(r1, 1001L, 2);
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.reintegrate(1001L))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), 1001L))
                    .as("message '%s' contient l'id", e.getMessage()).isTrue();
                assertThat(e.getMessage()).contains("ACTIVE");
            });

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA51 - coureur WINNER : BusinessConflictException (id et statut), aucun save")
    void ca51_winnerRunnerIsRejected() {
        Runner a = asWinner(givenRunner(r1, 1001L, 2));
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.reintegrate(1001L))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), 1001L))
                    .as("message '%s' contient l'id", e.getMessage()).isTrue();
                assertThat(e.getMessage()).contains("WINNER");
            });

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
    }

    @Test
    @DisplayName("CA51 - course SETUP : BusinessConflictException, aucun save")
    void ca51_setupRaceIsRejected() {
        Race setup = race(301L, 6706, 3600, 50, RaceStatus.SETUP, null);
        Runner a = asDnf(runner(1001L, setup), DnfReason.VOLUNTARY, 1);
        repos.withRaces(setup).withRunners(a);
        clock.set(at("07:30:00"));

        assertThatThrownBy(() -> service.reintegrate(1001L))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
    }

    @Test
    @DisplayName("CA51 - course FINISHED : BusinessConflictException, aucun save")
    void ca51_finishedRaceIsRejected() {
        Race finished = race(302L, 6706, 3600, 50, RaceStatus.FINISHED, T0);
        Runner a = asDnf(givenRunner(finished, 1001L, 4), DnfReason.TIMEOUT, 5);
        clock.set(at("13:00:20"));

        assertThatThrownBy(() -> service.reintegrate(1001L))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfYard()).isEqualTo(5);
    }

    @Test
    @DisplayName("CA51 - id inconnu : ResourceNotFoundException, aucun save")
    void ca51_unknownRunner() {
        givenRunner(r1, 1001L, 2);
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.reintegrate(987_654L))
            .isInstanceOf(ResourceNotFoundException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 987_654L))
                .as("message '%s' contient l'id", e.getMessage()).isTrue());

        repos.assertNoWrite();
    }
}
