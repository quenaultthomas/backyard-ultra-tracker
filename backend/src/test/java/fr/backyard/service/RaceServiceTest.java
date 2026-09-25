package fr.backyard.service;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.ResourceNotFoundException;
import fr.backyard.testsupport.FakeRepositories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.mentionsNumber;
import static fr.backyard.testsupport.TestData.namedRace;
import static fr.backyard.testsupport.TestData.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec increment 3 - RG9 a RG13, CA11 a CA20 (parties [unit]). Repositories mockes, Clock fixe. */
class RaceServiceTest {

    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);

    private final FakeRepositories repos = new FakeRepositories();
    private final RaceService service = new RaceService(repos.raceRepository, repos.runnerRepository,
        Clock.fixed(T0, ZoneOffset.UTC));

    private static RaceCommand command(String name, LocalDate date, int distance, int duration, int elevation) {
        return new RaceCommand(name, date, distance, duration, elevation);
    }

    private static RaceCommand backyardTestCommand() {
        return command("Backyard Test", OCT_3, 6706, 3600, 50);
    }

    @Test
    @DisplayName("CA11 - creation : course sauvegardee au statut SETUP, sans startedAt, avec les parametres demandes")
    void ca11_createRace() {
        Race created = service.create(backyardTestCommand());

        assertThat(repos.savedRaces()).containsExactly(created);
        assertThat(created.getStatus()).isEqualTo(RaceStatus.SETUP);
        assertThat(created.getStartedAt()).isNull();
        assertThat(created.getName()).isEqualTo("Backyard Test");
        assertThat(created.getRaceDate()).isEqualTo(OCT_3);
        assertThat(created.getLoopDistance()).isEqualTo(6706);
        assertThat(created.getLoopDuration()).isEqualTo(3600);
        assertThat(created.getLoopElevation()).isEqualTo(50);
    }

    @Test
    @DisplayName("CA11 - creation avec un nom deja pris : BusinessConflictException citant le nom, aucun save")
    void ca11_duplicateNameIsRejected() {
        repos.withRaces(backyardTest(RaceStatus.SETUP));

        assertThatThrownBy(() -> service.create(backyardTestCommand()))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining("Backyard Test");

        assertThat(repos.savedRaces()).isEmpty();
    }

    @Test
    @DisplayName("CA12 - liste triee par raceDate croissante puis id croissant : C3, C2, C1")
    void ca12_listIsSortedByDateThenId() {
        Race c1 = namedRace(1L, "C1", LocalDate.of(2026, 11, 1), 6706, 3600, 50, RaceStatus.SETUP, null);
        Race c2 = namedRace(3L, "C2", OCT_3, 6706, 3600, 50, RaceStatus.SETUP, null);
        Race c3 = namedRace(2L, "C3", OCT_3, 6706, 3600, 50, RaceStatus.SETUP, null);
        repos.withRaces(c1, c2, c3);

        List<Race> races = service.list();

        assertThat(races).containsExactly(c3, c2, c1);
    }

    @Test
    @DisplayName("CA13 - get(99) sur repository vide : ResourceNotFoundException contenant 99")
    void ca13_getUnknownRace() {
        assertThatThrownBy(() -> service.get(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .satisfies(e -> assertThat(mentionsNumber(e.getMessage(), 99)).isTrue());
    }

    @Test
    @DisplayName("CA13 / RG10 - get(1) renvoie la course")
    void ca13_getExistingRace() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        repos.withRaces(r1);

        assertThat(service.get(1L)).isSameAs(r1);
    }

    @Test
    @DisplayName("CA14 - modification en SETUP : les cinq valeurs sont remplacees")
    void ca14_updateSetupRace() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1);

        Race updated = service.update(1L, command("Backyard 2026", LocalDate.of(2026, 10, 4), 5000, 3000, 30));

        assertThat(updated).isSameAs(r1);
        assertThat(r1.getName()).isEqualTo("Backyard 2026");
        assertThat(r1.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(r1.getLoopDistance()).isEqualTo(5000);
        assertThat(r1.getLoopDuration()).isEqualTo(3000);
        assertThat(r1.getLoopElevation()).isEqualTo(30);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.SETUP);
    }

    @ParameterizedTest(name = "CA15 - course {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA15 - parametres de boucle modifies hors SETUP : BusinessConflictException citant le statut, aucun save")
    void ca15_loopParametersAreFrozenOutsideSetup(RaceStatus status) {
        Race r1 = backyardTest(status);
        repos.withRaces(r1);

        assertThatThrownBy(() -> service.update(1L, command("Backyard Test", OCT_3, 6706, 1800, 50)))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining(status.name());

        assertThat(repos.savedRaces()).isEmpty();
        assertThat(r1.getLoopDuration()).isEqualTo(3600);
    }

    @ParameterizedTest(name = "CA15 - course {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA15 - memes parametres de boucle hors SETUP : nom et date modifies")
    void ca15_nameAndDateRemainEditable(RaceStatus status) {
        Race r1 = backyardTest(status);
        repos.withRaces(r1);

        service.update(1L, command("Backyard renommée", LocalDate.of(2026, 10, 5), 6706, 3600, 50));

        assertThat(r1.getName()).isEqualTo("Backyard renommée");
        assertThat(r1.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r1.getLoopDuration()).isEqualTo(3600);
        assertThat(r1.getStatus()).isEqualTo(status);
        assertThat(r1.getStartedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("CA16 - renommer avec le nom d'une autre course : BusinessConflictException")
    void ca16_nameTakenByAnotherRace() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1, namedRace(2L, "Autre course", OCT_3, 6706, 3600, 50, RaceStatus.SETUP, null));

        assertThatThrownBy(() -> service.update(1L, command("Autre course", OCT_3, 6706, 3600, 50)))
            .isInstanceOf(BusinessConflictException.class);

        assertThat(r1.getName()).isEqualTo("Backyard Test");
        assertThat(repos.savedRaces()).isEmpty();
    }

    @Test
    @DisplayName("CA16 / CL5 - renommer avec son propre nom : accepte")
    void ca16_ownNameIsAccepted() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1);

        Race updated = service.update(1L, command("Backyard Test", LocalDate.of(2026, 10, 4), 6706, 3600, 50));

        assertThat(updated.getName()).isEqualTo("Backyard Test");
        assertThat(updated.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("CA14 / RG11 - modification d'une course inconnue : ResourceNotFoundException")
    void ca14_updateUnknownRace() {
        assertThatThrownBy(() -> service.update(99L, backyardTestCommand()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("CA17 - suppression d'une course SETUP avec 2 coureurs : coureurs supprimes puis la course")
    void ca17_deleteSetupRaceWithRunners() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        Runner a = runner(11L, r1, 1, "Alice", "tok-a");
        Runner b = runner(12L, r1, 2, "Bob", "tok-b");
        repos.withRaces(r1).withRunners(a, b);

        service.delete(1L);

        assertThat(repos.deletedEntities()).hasSize(3);
        assertThat(repos.deletedEntities().subList(0, 2)).containsExactlyInAnyOrder(a, b);
        assertThat(repos.deletedEntities().get(2)).isSameAs(r1);
        assertThat(repos.races()).isEmpty();
        assertThat(repos.runners()).isEmpty();
    }

    @ParameterizedTest(name = "CA18 - course {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA18 - suppression d'une course RUNNING ou FINISHED : BusinessConflictException citant le statut, aucune suppression")
    void ca18_deleteNonSetupRaceIsRejected(RaceStatus status) {
        Race r1 = backyardTest(status);
        repos.withRaces(r1).withRunners(runner(11L, r1, 1, "Alice", "tok-a"));

        assertThatThrownBy(() -> service.delete(1L))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining(status.name());

        assertThat(repos.deletedEntities()).isEmpty();
        assertThat(repos.races()).containsExactly(r1);
    }

    @Test
    @DisplayName("CA18 - suppression d'une course inconnue : ResourceNotFoundException")
    void ca18_deleteUnknownRace() {
        assertThatThrownBy(() -> service.delete(99L))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(repos.deletedEntities()).isEmpty();
    }

    @Test
    @DisplayName("CA19 - demarrage d'une course SETUP : RUNNING, startedAt = horloge (08:00:00Z), course sauvegardee")
    void ca19_startSetupRace() {
        Race r1 = backyardTest(RaceStatus.SETUP);
        repos.withRaces(r1);

        Race started = service.start(1L);

        assertThat(started).isSameAs(r1);
        assertThat(r1.getStatus()).isEqualTo(RaceStatus.RUNNING);
        assertThat(r1.getStartedAt()).isEqualTo(T0);
        assertThat(repos.savedRaces()).containsExactly(r1);
    }

    @ParameterizedTest(name = "CA20 - course {0}")
    @EnumSource(value = RaceStatus.class, names = {"RUNNING", "FINISHED"})
    @DisplayName("CA20 - demarrage refuse hors SETUP : BusinessConflictException citant le statut, startedAt inchange, aucun save")
    void ca20_startNonSetupRaceIsRejected(RaceStatus status) {
        Race r1 = namedRace(1L, "Backyard Test", OCT_3, 6706, 3600, 50, status,
            java.time.Instant.parse("2026-10-03T07:00:00Z"));
        repos.withRaces(r1);

        assertThatThrownBy(() -> service.start(1L))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessageContaining(status.name());

        assertThat(r1.getStatus()).isEqualTo(status);
        assertThat(r1.getStartedAt()).isEqualTo(java.time.Instant.parse("2026-10-03T07:00:00Z"));
        assertThat(repos.savedRaces()).isEmpty();
    }

    @Test
    @DisplayName("CA20 - demarrage d'une course inconnue : ResourceNotFoundException")
    void ca20_startUnknownRace() {
        assertThatThrownBy(() -> service.start(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
