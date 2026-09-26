package fr.backyard.it;

import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mode rattrapage INC-3 : les méthodes de repository ajoutées par la spec de l'incrément 3
 * ({@code existsByName}, {@code existsByNameAndIdNot}, {@code existsByRaceIdAndBib},
 * {@code existsByRaceIdAndBibAndIdNot}, {@code existsByRunnerId}, {@code findByRunnerRaceId}) ne sont
 * exercées, dans les tests existants, qu'à travers des repositories Mockito mockés
 * ({@code RaceServiceTest}, {@code RunnerServiceTest}) ou une fausse implémentation en mémoire
 * ({@code FakeRepositories}). Aucun test existant ne vérifie que ces méthodes dérivées Spring Data
 * produisent la requête attendue contre un vrai schéma (dérivation du nom de méthode, notamment le
 * chemin imbriqué {@code runner.race.id} de {@code findByRunnerRaceId}).
 */
@Tag("INC-3")
class RepositoryDerivedQueriesIT extends AbstractApiIT {

    // ── RaceRepository.existsByName (RG9 inc. 3) ────────────────────────────────────────────────
    @Test
    @Tag("INC3-CA11")
    @DisplayName("CA11 - RaceRepository.existsByName detecte un nom de course deja present en base")
    void existsByNameDetectsExistingRaceName() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT Repo ExistsByName", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        trackRaceForCleanup(race.getId());

        assertThat(raceRepository.existsByName("IT Repo ExistsByName")).isTrue();
        assertThat(raceRepository.existsByName("IT Repo Inconnu")).isFalse();
    }

    // ── RaceRepository.existsByNameAndIdNot (RG11 inc. 3) ───────────────────────────────────────
    @Test
    @Tag("INC3-CA16")
    @DisplayName("CA16 - RaceRepository.existsByNameAndIdNot exclut la course elle-meme mais detecte "
        + "une autre course du meme nom")
    void existsByNameAndIdNotExcludesOwnRace() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT Repo ExistsByNameAndIdNot", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Race other = raceRepository.saveAndFlush(
            new Race("IT Repo Autre Course", LocalDate.of(2026, 10, 2), 6700, 3600, 0));
        trackRaceForCleanup(race.getId());
        trackRaceForCleanup(other.getId());

        assertThat(raceRepository.existsByNameAndIdNot("IT Repo ExistsByNameAndIdNot", race.getId()))
            .as("la course ne doit pas se detecter elle-meme").isFalse();
        assertThat(raceRepository.existsByNameAndIdNot("IT Repo Autre Course", race.getId()))
            .as("une autre course du meme nom doit etre detectee").isTrue();
    }

    // ── RunnerRepository.existsByRaceIdAndBibAndIdNot (RG18 inc. 3) ─────────────────────────────
    @Test
    @Tag("INC3-CA29")
    @DisplayName("CA29 - RunnerRepository.existsByRaceIdAndBibAndIdNot exclut le coureur lui-meme mais "
        + "detecte un dossard deja attribue a un autre coureur de la course")
    void existsByRaceIdAndBibAndIdNotExcludesOwnRunner() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT Repo ExistsBibAndIdNot", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        trackRaceForCleanup(race.getId());
        Runner alice = runnerRepository.saveAndFlush(new Runner(race, 6, "Alice", "tok-it-bib-alice"));
        Runner bob = runnerRepository.saveAndFlush(new Runner(race, 7, "Bob", "tok-it-bib-bob"));

        assertThat(runnerRepository.existsByRaceIdAndBibAndIdNot(race.getId(), 6, alice.getId()))
            .as("Alice ne doit pas se detecter elle-meme").isFalse();
        assertThat(runnerRepository.existsByRaceIdAndBibAndIdNot(race.getId(), 7, alice.getId()))
            .as("le dossard 7 de Bob doit etre detecte pour un changement de dossard d'Alice").isTrue();
        assertThat(runnerRepository.existsByRaceIdAndBibAndIdNot(race.getId(), 9, alice.getId())).isFalse();

        runnerRepository.delete(bob);
        runnerRepository.delete(alice);
    }

    // ── RunnerRepository.existsByRaceIdAndBib : declaree par la spec (RG20 inc. 3) mais non appelee
    // par le code de production (RunnerService n'utilise que la variante AndIdNot). Verification
    // defensive de la requete derivee elle-meme, en l'absence de tout CA qui l'exerce directement.
    @Test
    @DisplayName("Revue - RunnerRepository.existsByRaceIdAndBib (methode autorisee par la spec, non "
        + "appelee par le code de production) fonctionne correctement contre le vrai schema")
    void existsByRaceIdAndBibDetectsDuplicateBib() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT Repo ExistsBib", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        trackRaceForCleanup(race.getId());
        Runner alice = runnerRepository.saveAndFlush(new Runner(race, 3, "Alice", "tok-it-existsbib-alice"));

        assertThat(runnerRepository.existsByRaceIdAndBib(race.getId(), 3)).isTrue();
        assertThat(runnerRepository.existsByRaceIdAndBib(race.getId(), 99)).isFalse();

        runnerRepository.delete(alice);
    }

    // ── PassageRepository.existsByRunnerId (RG19 inc. 3) ────────────────────────────────────────
    @Test
    @Tag("INC3-CA31")
    @DisplayName("CA31 - PassageRepository.existsByRunnerId detecte qu'un coureur a au moins un passage")
    void existsByRunnerIdDetectsPassages() {
        Race race = raceRepository.saveAndFlush(
            new Race("IT Repo ExistsPassage", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        trackRaceForCleanup(race.getId());
        Runner withPassage = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-it-exists-passage"));
        Runner withoutPassage = runnerRepository.saveAndFlush(new Runner(race, 2, "Bob", "tok-it-no-passage"));
        passageRepository.saveAndFlush(
            new Passage(withPassage, 1, PassageSource.SCAN, Instant.parse("2026-10-01T08:00:00Z")));

        assertThat(passageRepository.existsByRunnerId(withPassage.getId())).isTrue();
        assertThat(passageRepository.existsByRunnerId(withoutPassage.getId())).isFalse();
    }

    // ── PassageRepository.findByRunnerRaceId (RG24 inc. 3 : tableau de bord, une seule requete) ──
    @Test
    @Tag("INC3-CA40")
    @DisplayName("CA40 - PassageRepository.findByRunnerRaceId renvoie, en une seule requete, tous les "
        + "passages de tous les coureurs d'une course, et aucun passage d'une autre course")
    void findByRunnerRaceIdReturnsAllPassagesAcrossRunnersOfOneRace() {
        Race race1 = raceRepository.saveAndFlush(
            new Race("IT Repo FindByRunnerRaceId 1", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Race race2 = raceRepository.saveAndFlush(
            new Race("IT Repo FindByRunnerRaceId 2", LocalDate.of(2026, 10, 2), 6700, 3600, 0));
        trackRaceForCleanup(race1.getId());
        trackRaceForCleanup(race2.getId());
        Runner alice = runnerRepository.saveAndFlush(new Runner(race1, 1, "Alice", "tok-it-frr-alice"));
        Runner bob = runnerRepository.saveAndFlush(new Runner(race1, 2, "Bob", "tok-it-frr-bob"));
        Runner carol = runnerRepository.saveAndFlush(new Runner(race2, 1, "Carol", "tok-it-frr-carol"));
        passageRepository.saveAndFlush(
            new Passage(alice, 1, PassageSource.SCAN, Instant.parse("2026-10-01T08:45:00Z")));
        passageRepository.saveAndFlush(
            new Passage(bob, 1, PassageSource.SCAN, Instant.parse("2026-10-01T08:50:00Z")));
        passageRepository.saveAndFlush(
            new Passage(carol, 1, PassageSource.SCAN, Instant.parse("2026-10-02T08:45:00Z")));

        assertThat(passageRepository.findByRunnerRaceId(race1.getId()))
            .hasSize(2)
            .allMatch(passage -> passage.getRunner().getId().equals(alice.getId())
                || passage.getRunner().getId().equals(bob.getId()));
        assertThat(passageRepository.findByRunnerRaceId(race2.getId())).hasSize(1);
    }
}
