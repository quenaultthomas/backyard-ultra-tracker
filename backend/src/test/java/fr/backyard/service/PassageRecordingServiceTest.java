package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.FakeRepositories;
import fr.backyard.testsupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

/** Spec increment 2 - RG12 a RG15, CA17 a CA25. Repositories mockes, horloge controlee. */
class PassageRecordingServiceTest {

    private static final long R1_ID = 101L;

    private final FakeRepositories repos = new FakeRepositories();
    private final MutableClock clock = new MutableClock(T0);
    private final PassageRecordingService service =
        new PassageRecordingService(repos.runnerRepository, repos.passageRepository, clock);

    private final Race r1 = r1(R1_ID);

    private Runner givenRunner(long id, String qrToken, int... yardsAlreadyScanned) {
        Runner runner = runner(id, r1, qrToken);
        repos.withRaces(r1).withRunners(runner);
        for (int yard : yardsAlreadyScanned) {
            repos.withPassages(scanInWindow(runner, yard));
        }
        return runner;
    }

    @Test
    @DisplayName("CA17 - scan nominal : passage yard 1, SCAN, scannedAt 08:45 enregistre et renvoye")
    void ca17_nominalScan() {
        Runner runner = givenRunner(7001L, "tok-a");
        clock.set(at("08:46:00"));

        Passage passage = service.recordScan("tok-a", at("08:45:00"));

        assertThat(passage.getYardNumber()).isEqualTo(1);
        assertThat(passage.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(passage.getScannedAt()).isEqualTo(at("08:45:00"));
        assertThat(passage.getRunner()).isSameAs(runner);
        assertThat(repos.savedPassages()).containsExactly(passage);
        assertThat(repos.passagesOf(runner)).containsExactly(passage);
    }

    @Test
    @DisplayName("CA18 - scan a 08:59:59.999 recu a 09:00:30 : attribue au yard 1")
    void ca18_scanJustBeforeBellIsYardOne() {
        givenRunner(7001L, "tok-a");
        clock.set(at("09:00:30"));

        Passage passage = service.recordScan("tok-a", at("08:59:59.999"));

        assertThat(passage.getYardNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA18 - scan a 09:00:00.000 (coureur avec passage yard 1) : attribue au yard 2")
    void ca18_scanOnBellIsNextYard() {
        givenRunner(7002L, "tok-b", 1);
        clock.set(at("09:00:30"));

        Passage passage = service.recordScan("tok-b", at("09:00:00.000"));

        assertThat(passage.getYardNumber()).isEqualTo(2);
        assertThat(passage.getScannedAt()).isEqualTo(at("09:00:00.000"));
    }

    @Test
    @DisplayName("CA19 - scan effectue a 10:59:50 recu a 11:00:40, coureur encore ACTIVE : yard 3, coureur ACTIVE non sauvegarde")
    void ca19_lateReceivedScanOfActiveRunner() {
        Runner runner = givenRunner(7001L, "tok-a", 1, 2);
        clock.set(at("11:00:40"));

        Passage passage = service.recordScan("tok-a", at("10:59:50"));

        assertThat(passage.getYardNumber()).isEqualTo(3);
        assertThat(repos.savedPassages()).containsExactly(passage);
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(repos.savedRunners()).isEmpty();
    }

    @Test
    @DisplayName("CA20 - scan tardif (yard 3 sans passage yard 2) : BusinessConflictException citant le yard 2, aucun save")
    void ca20_lateScanWithoutPreviousYardIsRejected() {
        Runner runner = givenRunner(7001L, "tok-a", 1);
        clock.set(at("10:00:10"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("10:00:05")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 2))
                .as("message '%s' mentionne le yard 2", e.getMessage()).isTrue());

        repos.assertNoWrite();
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(repos.passagesOf(runner)).hasSize(1);
    }

    @Test
    @DisplayName("CA21 - coureur DNF VOLUNTARY : BusinessConflictException (id, DNF, VOLUNTARY), aucun save, statut inchange")
    void ca21_voluntaryDnfRunnerIsRejected() {
        Runner runner = givenRunner(7001L, "tok-a", 1);
        asDnf(runner, DnfReason.VOLUNTARY, 2);
        clock.set(at("10:00:30"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("09:59:00")))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> {
                assertThat(mentionsNumber(e.getMessage(), 7001L))
                    .as("message '%s' contient l'id du coureur", e.getMessage()).isTrue();
                assertThat(e.getMessage()).contains("DNF").contains("VOLUNTARY");
            });

        repos.assertNoWrite();
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(runner.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(runner.getDnfYard()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA21 - coureur WINNER (course RUNNING) : BusinessConflictException citant WINNER, aucun save")
    void ca21_winnerRunnerIsRejected() {
        Runner runner = givenRunner(7001L, "tok-a", 1, 2);
        asWinner(runner);
        clock.set(at("10:00:30"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("10:00:20")))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining("WINNER");

        repos.assertNoWrite();
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.WINNER);
    }

    @Test
    @DisplayName("CA22 - course SETUP : BusinessConflictException, aucun save")
    void ca22_setupRaceIsRejected() {
        Race setup = race(301L, 6706, 3600, 50, RaceStatus.SETUP, null);
        Runner runner = runner(7001L, setup, "tok-a");
        repos.withRaces(setup).withRunners(runner);
        clock.set(at("08:46:00"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("08:45:00")))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA22 - course FINISHED : BusinessConflictException, aucun save")
    void ca22_finishedRaceIsRejected() {
        Race finished = race(302L, 6706, 3600, 50, RaceStatus.FINISHED, T0);
        Runner runner = runner(7001L, finished, "tok-a");
        repos.withRaces(finished).withRunners(runner);
        clock.set(at("08:46:00"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("08:45:00")))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(finished.getStatus()).isEqualTo(RaceStatus.FINISHED);
    }

    @Test
    @DisplayName("CA23 - QR inconnu : ResourceNotFoundException dont le message contient le token")
    void ca23_unknownQrToken() {
        givenRunner(7001L, "tok-a");
        clock.set(at("08:46:00"));

        assertThatThrownBy(() -> service.recordScan("tok-inconnu", at("08:45:00")))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("tok-inconnu");

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA24 - scannedAt null : InvalidInputException, aucun save")
    void ca24_nullScannedAt() {
        givenRunner(7001L, "tok-a");
        clock.set(at("08:45:00"));

        assertThatThrownBy(() -> service.recordScan("tok-a", null))
            .isInstanceOf(InvalidInputException.class);

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA24 - scan dans le futur (08:45:01 pour une horloge a 08:45:00) : InvalidInputException, aucun save")
    void ca24_scanInTheFuture() {
        givenRunner(7001L, "tok-a");
        clock.set(at("08:45:00"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("08:45:01")))
            .isInstanceOf(InvalidInputException.class);

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA24 - scan a now exactement (08:45:00) : accepte (seul scannedAt > now est rejete)")
    void ca24_scanAtNowIsAccepted() {
        givenRunner(7001L, "tok-a");
        clock.set(at("08:45:00"));

        Passage passage = service.recordScan("tok-a", at("08:45:00"));

        assertThat(passage.getYardNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA24 - scan anterieur au depart (07:59:59) : InvalidInputException, aucun save")
    void ca24_scanBeforeStart() {
        givenRunner(7001L, "tok-a");
        clock.set(at("08:45:00"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("07:59:59")))
            .isInstanceOf(InvalidInputException.class);

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA25 - doublon avec le meme scannedAt : passage existant renvoye, aucun save")
    void ca25_identicalDuplicateIsIdempotent() {
        Runner runner = givenRunner(7001L, "tok-a");
        Passage existing = scan(runner, 1, at("08:45:00"));
        repos.withPassages(existing);
        clock.set(at("08:50:30"));

        Passage passage = service.recordScan("tok-a", Instant.parse("2026-10-03T08:45:00Z"));

        assertThat(passage).isSameAs(existing);
        repos.assertNoWrite();
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA25 - second scan different sur le meme yard : BusinessConflictException, aucun save")
    void ca25_differentScanOnSameYardIsRejected() {
        Runner runner = givenRunner(7001L, "tok-a");
        Passage existing = scan(runner, 1, at("08:45:00"));
        repos.withPassages(existing);
        clock.set(at("08:50:30"));

        assertThatThrownBy(() -> service.recordScan("tok-a", at("08:50:00")))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(repos.passagesOf(runner)).containsExactly(existing);
        assertThat(existing.getScannedAt()).isEqualTo(at("08:45:00"));
    }
}
