package fr.backyard.service;

import fr.backyard.domain.DnfReason;
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

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.asWinner;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.r1;
import static fr.backyard.testsupport.TestData.race;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scanInWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec increment 2 - RG22, RG29, CA40 a CA43. Repositories mockes, horloge controlee. */
class ManualDnfServiceTest {

    private final FakeRepositories repos = new FakeRepositories();
    private final MutableClock clock = new MutableClock(T0);
    private final ManualDnfService service =
        new ManualDnfService(repos.runnerRepository, repos.passageRepository, clock);

    private final Race r1 = r1(101L);

    private Runner givenRunner(Race race, long id, int lastYard) {
        Runner runner = runner(id, race);
        repos.withRaces(race).withRunners(runner);
        for (int yard = 1; yard <= lastYard; yard++) {
            repos.withPassages(scanInWindow(runner, yard));
        }
        return runner;
    }

    @Test
    @DisplayName("CA40 - DNF manuel en cours de boucle (10:20, passages 1 et 2) : DNF VOLUNTARY dnfYard 3, aucun passage cree")
    void ca40_manualDnfDuringLoop() {
        Runner a = givenRunner(r1, 1001L, 2);
        clock.set(at("10:20:00"));

        Runner result = service.declareDnf(1001L, DnfReason.VOLUNTARY);

        assertThat(result).isSameAs(a);
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.VOLUNTARY);
        assertThat(a.getDnfYard()).isEqualTo(3);
        assertThat(repos.savedPassages()).isEmpty();
        assertThat(repos.passagesOf(a)).hasSize(2);
        repos.assertNoPassageDeletion();
    }

    @Test
    @DisplayName("CA41 - DNF manuel apres avoir termine le yard courant (10:50, passages 1 a 3) : dnfYard 4")
    void ca41_manualDnfAfterFinishingCurrentYard() {
        Runner a = givenRunner(r1, 1001L, 3);
        clock.set(at("10:50:00"));

        service.declareDnf(1001L, DnfReason.OTHER);

        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.OTHER);
        assertThat(a.getDnfYard()).isEqualTo(4);
        assertThat(repos.savedPassages()).isEmpty();
    }

    @Test
    @DisplayName("CA42 - DNF manuel sans passage (08:30) : dnfYard 1")
    void ca42_manualDnfWithoutPassage() {
        Runner a = givenRunner(r1, 1001L, 0);
        clock.set(at("08:30:00"));

        service.declareDnf(1001L, DnfReason.MANUAL);

        assertThat(a.getStatus()).isEqualTo(RunnerStatus.DNF);
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.MANUAL);
        assertThat(a.getDnfYard()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA43 - raison TIMEOUT refusee : InvalidInputException, aucun save")
    void ca43_timeoutReasonIsRejected() {
        Runner a = givenRunner(r1, 1001L, 1);
        clock.set(at("09:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, DnfReason.TIMEOUT))
            .isInstanceOf(InvalidInputException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA43 - raison null refusee : InvalidInputException, aucun save")
    void ca43_nullReasonIsRejected() {
        Runner a = givenRunner(r1, 1001L, 1);
        clock.set(at("09:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, null))
            .isInstanceOf(InvalidInputException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA43 - coureur inconnu : ResourceNotFoundException dont le message contient l'id, aucun save")
    void ca43_unknownRunner() {
        givenRunner(r1, 1001L, 1);
        clock.set(at("09:30:00"));

        assertThatThrownBy(() -> service.declareDnf(987_654L, DnfReason.VOLUNTARY))
            .isInstanceOf(ResourceNotFoundException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 987_654L))
                .as("message '%s' contient l'id", e.getMessage()).isTrue());

        repos.assertNoWrite();
    }

    @Test
    @DisplayName("CA43 - coureur deja DNF : BusinessConflictException, aucun save, DNF inchange")
    void ca43_alreadyDnfRunner() {
        Runner a = asDnf(givenRunner(r1, 1001L, 1), DnfReason.TIMEOUT, 2);
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, DnfReason.VOLUNTARY))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getDnfReason()).isEqualTo(DnfReason.TIMEOUT);
        assertThat(a.getDnfYard()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA43 - coureur WINNER : BusinessConflictException, aucun save")
    void ca43_winnerRunner() {
        Runner a = asWinner(givenRunner(r1, 1001L, 2));
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, DnfReason.VOLUNTARY))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.WINNER);
    }

    @Test
    @DisplayName("CA43 - course SETUP : BusinessConflictException, aucun save")
    void ca43_setupRace() {
        Race setup = race(301L, 6706, 3600, 50, RaceStatus.SETUP, null);
        Runner a = runner(1001L, setup);
        repos.withRaces(setup).withRunners(a);
        clock.set(at("07:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, DnfReason.VOLUNTARY))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA43 - course FINISHED : BusinessConflictException, aucun save")
    void ca43_finishedRace() {
        Race finished = race(302L, 6706, 3600, 50, RaceStatus.FINISHED, T0);
        Runner a = givenRunner(finished, 1001L, 1);
        clock.set(at("10:30:00"));

        assertThatThrownBy(() -> service.declareDnf(1001L, DnfReason.VOLUNTARY))
            .isInstanceOf(BusinessConflictException.class);

        repos.assertNoWrite();
        assertThat(a.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }
}
