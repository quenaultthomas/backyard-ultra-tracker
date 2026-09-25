package fr.backyard.service;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec increment 3 - RG15 a RG19, CA23, CA25, CA28, CA29, CA31 (parties [unit]). Repositories mockes. */
class RunnerServiceTest {

    private static final String TOKEN = "3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f";

    private final FakeRepositories repos = new FakeRepositories();
    private final QrTokenGenerator qrTokenGenerator = mock(QrTokenGenerator.class);
    private final RunnerService service = new RunnerService(repos.raceRepository, repos.runnerRepository,
        repos.passageRepository, qrTokenGenerator);

    @Test
    @DisplayName("CA23 - inscription avec dossards 1, 2 et 5 existants : bib 6, token du generateur, ACTIVE, champs DNF null")
    void ca23_registrationTakesMaxBibPlusOne() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1).withRunners(
            runner(21L, r1, 1, "Un", "tok-1"), runner(22L, r1, 2, "Deux", "tok-2"), runner(25L, r1, 5, "Cinq", "tok-5"));
        when(qrTokenGenerator.generate()).thenReturn(TOKEN);

        Runner registered = service.register(1L, "Alice");

        assertThat(repos.savedRunners()).containsExactly(registered);
        assertThat(registered.getRace()).isSameAs(r1);
        assertThat(registered.getBib()).isEqualTo(6);
        assertThat(registered.getName()).isEqualTo("Alice");
        assertThat(registered.getQrToken()).isEqualTo(TOKEN);
        assertThat(registered.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(registered.getDnfReason()).isNull();
        assertThat(registered.getDnfYard()).isNull();
    }

    @Test
    @DisplayName("CA23 - premiere inscription sur une course sans coureur : bib 1")
    void ca23_firstRegistrationGetsBibOne() {
        repos.withRaces(backyardTest(RaceStatus.SETUP));
        when(qrTokenGenerator.generate()).thenReturn(TOKEN);

        Runner registered = service.register(1L, "Alice");

        assertThat(registered.getBib()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA23 / RG15 - les dossards des autres courses ne comptent pas")
    void ca23_bibIsComputedPerRace() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Race other = fr.backyard.testsupport.TestData.namedRace(2L, "Autre", r1.getRaceDate(), 5000, 3600, 0,
            RaceStatus.SETUP, null);
        repos.withRaces(r1, other).withRunners(runner(31L, other, 9, "Ailleurs", "tok-9"));
        when(qrTokenGenerator.generate()).thenReturn(TOKEN);

        assertThat(service.register(1L, "Alice").getBib()).isEqualTo(1);
    }

    @ParameterizedTest(name = "CA25 - course {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA25 - inscription sur une course RUNNING ou FINISHED : conflit inscriptions fermees, aucun save")
    void ca25_registrationClosed(RaceStatus status) {
        repos.withRaces(backyardTest(status));
        when(qrTokenGenerator.generate()).thenReturn(TOKEN);

        assertThatThrownBy(() -> service.register(1L, "Bob"))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase("inscriptions fermées"));

        assertThat(repos.savedRunners()).isEmpty();
    }

    @Test
    @DisplayName("CA25 - inscription sur une course inconnue : ResourceNotFoundException, aucun save")
    void ca25_registrationOnUnknownRace() {
        assertThatThrownBy(() -> service.register(99L, "Bob"))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repos.savedRunners()).isEmpty();
        verify(qrTokenGenerator, never()).generate();
    }

    @Test
    @DisplayName("CA28 - liste des coureurs d'une course triee par dossard : 1, 3, 5")
    void ca28_listByRaceSortedByBib() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1).withRunners(
            runner(25L, r1, 5, "Cinq", "tok-5"), runner(21L, r1, 1, "Un", "tok-1"), runner(23L, r1, 3, "Trois", "tok-3"));

        assertThat(service.listByRace(1L)).extracting(Runner::getBib).containsExactly(1, 3, 5);
    }

    @Test
    @DisplayName("CA28 - liste des coureurs d'une course inconnue : ResourceNotFoundException")
    void ca28_listByUnknownRace() {
        assertThatThrownBy(() -> service.listByRace(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("CA28 - get(99) sans coureur : ResourceNotFoundException ; get(12) renvoie le coureur")
    void ca28_getRunner() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice);

        assertThat(service.get(12L)).isSameAs(alice);
        assertThatThrownBy(() -> service.get(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 99)).isTrue());
    }

    @Test
    @DisplayName("CA29 - course SETUP, dossard 7 libre : bib 7 et nom 'Alice B.'")
    void ca29_updateBibAndNameInSetup() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice);

        Runner updated = service.update(12L, 7, "Alice B.");

        assertThat(updated).isSameAs(alice);
        assertThat(alice.getBib()).isEqualTo(7);
        assertThat(alice.getName()).isEqualTo("Alice B.");
    }

    @Test
    @DisplayName("CA29 - dossard 3 deja attribue dans la course : BusinessConflictException citant le dossard 3")
    void ca29_bibAlreadyTaken() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice, runner(13L, r1, 3, "Charles", "tok-3"));

        assertThatThrownBy(() -> service.update(12L, 3, "Alice"))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 3))
                .as("message '%s' cite le dossard 3", e.getMessage()).isTrue());

        assertThat(alice.getBib()).isEqualTo(6);
        assertThat(repos.savedRunners()).isEmpty();
    }

    @Test
    @DisplayName("CA29 - course RUNNING, meme dossard : nom modifie")
    void ca29_nameEditableWhileRunning() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice);

        service.update(12L, 6, "Alice C.");

        assertThat(alice.getName()).isEqualTo("Alice C.");
        assertThat(alice.getBib()).isEqualTo(6);
    }

    @Test
    @DisplayName("CA29 - course RUNNING, dossard modifie : BusinessConflictException citant RUNNING, bib reste 6")
    void ca29_bibFrozenWhileRunning() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice);

        assertThatThrownBy(() -> service.update(12L, 9, "Alice"))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining("RUNNING");

        assertThat(alice.getBib()).isEqualTo(6);
        assertThat(repos.savedRunners()).isEmpty();
    }

    @Test
    @DisplayName("CA29 / RG18 - modification d'un coureur inconnu : ResourceNotFoundException")
    void ca29_updateUnknownRunner() {
        assertThatThrownBy(() -> service.update(99L, 7, "Alice"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("CA31 - suppression d'un coureur sans passage d'une course SETUP : coureur supprime")
    void ca31_deleteRunnerWithoutPassage() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice);

        service.delete(12L);

        assertThat(repos.deletedEntities()).containsExactly(alice);
    }

    @Test
    @DisplayName("CA31 - suppression d'un coureur d'une course RUNNING : BusinessConflictException, aucune suppression")
    void ca31_deleteRunnerOfRunningRace() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        repos.withRaces(r1).withRunners(runner(12L, r1, 6, "Alice", TOKEN));

        assertThatThrownBy(() -> service.delete(12L))
            .isInstanceOf(BusinessConflictException.class);

        assertThat(repos.deletedEntities()).isEmpty();
    }

    @Test
    @DisplayName("CA31 - suppression d'un coureur ayant des passages : BusinessConflictException citant les passages, aucune suppression")
    void ca31_deleteRunnerWithPassages() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner alice = runner(12L, r1, 6, "Alice", TOKEN);
        repos.withRaces(r1).withRunners(alice).withPassages(scan(alice, 1, at("08:45:00")));

        assertThatThrownBy(() -> service.delete(12L))
            .isInstanceOf(BusinessConflictException.class)
            .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase("passage"));

        assertThat(repos.deletedEntities()).isEmpty();
        repos.assertNoPassageDeletion();
    }

    @Test
    @DisplayName("CA31 / RG19 - suppression d'un coureur inconnu : ResourceNotFoundException")
    void ca31_deleteUnknownRunner() {
        assertThatThrownBy(() -> service.delete(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
