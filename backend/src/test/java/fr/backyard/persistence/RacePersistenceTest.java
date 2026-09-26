package fr.backyard.persistence;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.DnfReason;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class RacePersistenceTest {

    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RunnerRepository runnerRepository;

    @Autowired
    private PassageRepository passageRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Force l'écriture en base puis vide le cache de premier niveau : la lecture suivante
     * relit réellement les lignes depuis la base (aller-retour des types, réserve R1-2).
     */
    private void flushAndClearPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    // ── CA1 — Persistance minimale de Race ──────────────────────────────────
    @Test
    void ca1_persistMinimalRace() {
        Race race = new Race("Test Race", LocalDate.of(2026, 10, 1), 6700, 3600, 100);

        Race saved = raceRepository.save(race);
        raceRepository.flush();

        assertThat(saved.getId()).isNotNull();
        flushAndClearPersistenceContext();
        Race found = raceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getName()).isEqualTo("Test Race");
        assertThat(found.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(found.getStatus()).isEqualTo(RaceStatus.SETUP);
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getLoopDistance()).isEqualTo(6700);
        assertThat(found.getLoopDuration()).isEqualTo(3600);
        assertThat(found.getLoopElevation()).isEqualTo(100);
    }

    // ── CA2 — Statut SETUP par défaut ───────────────────────────────────────
    @Test
    void ca2_defaultStatusIsSetup() {
        Race race = new Race("Status Race", LocalDate.of(2026, 10, 1), 6700, 3600, 0);
        assertThat(race.getStatus()).isEqualTo(RaceStatus.SETUP);
    }

    // ── CA3 — started_at null à la création ─────────────────────────────────
    @Test
    void ca3_startedAtNullOnCreation() {
        Race race = new Race("Null StartedAt", LocalDate.of(2026, 10, 1), 6700, 3600, 50);
        Race saved = raceRepository.saveAndFlush(race);

        flushAndClearPersistenceContext();
        Race found = raceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getStartedAt()).isNull();
    }

    // ── CA4 — Unicité du nom de Race ────────────────────────────────────────
    @Test
    void ca4_duplicateRaceNameThrows() {
        raceRepository.saveAndFlush(new Race("Doublon", LocalDate.of(2026, 10, 1), 6700, 3600, 0));

        assertThatThrownBy(() ->
            raceRepository.saveAndFlush(new Race("Doublon", LocalDate.of(2026, 10, 2), 6700, 3600, 0))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── CA5 — Persistance minimale de Runner ────────────────────────────────
    @Test
    void ca5_persistMinimalRunner() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA5", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = new Runner(race, 1, "Alice", "tok-alice-001");

        Runner saved = runnerRepository.saveAndFlush(runner);

        assertThat(saved.getId()).isNotNull();
        flushAndClearPersistenceContext();
        Runner found = runnerRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getRace().getId()).isEqualTo(race.getId());
        assertThat(found.getBib()).isEqualTo(1);
        assertThat(found.getName()).isEqualTo("Alice");
        assertThat(found.getQrToken()).isEqualTo("tok-alice-001");
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    // ── CA6 — Statut ACTIVE par défaut pour Runner ──────────────────────────
    @Test
    void ca6_defaultRunnerStatusIsActive() {
        Race race = new Race("Race CA6", LocalDate.of(2026, 10, 1), 6700, 3600, 0);
        Runner runner = new Runner(race, 1, "Bob", "tok-bob-001");
        assertThat(runner.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
    }

    // ── CA7 — Unicité du dossard par course ─────────────────────────────────
    @Test
    void ca7_duplicateBibSameRaceThrows() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA7", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca7-alice"));

        assertThatThrownBy(() ->
            runnerRepository.saveAndFlush(new Runner(race, 1, "Bob", "tok-ca7-bob"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── CA8 — Dossard identique dans deux courses différentes autorisé ───────
    @Test
    void ca8_sameBibDifferentRacesAllowed() {
        Race race1 = raceRepository.saveAndFlush(new Race("Race CA8-1", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Race race2 = raceRepository.saveAndFlush(new Race("Race CA8-2", LocalDate.of(2026, 10, 2), 6700, 3600, 0));

        Runner r1 = runnerRepository.saveAndFlush(new Runner(race1, 1, "Alice", "tok-ca8-alice"));
        Runner r2 = runnerRepository.saveAndFlush(new Runner(race2, 1, "Bob", "tok-ca8-bob"));

        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    // ── CA9 — Unicité du qr_token ───────────────────────────────────────────
    @Test
    void ca9_duplicateQrTokenThrows() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA9", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-dup"));

        assertThatThrownBy(() ->
            runnerRepository.saveAndFlush(new Runner(race, 2, "Bob", "tok-dup"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── CA10 — Champs DNF nullables pour un runner ACTIVE ───────────────────
    @Test
    void ca10_dnfFieldsNullableForActiveRunner() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA10", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca10-alice"));

        flushAndClearPersistenceContext();
        Runner found = runnerRepository.findById(runner.getId()).orElseThrow();
        assertThat(found).isNotSameAs(runner);
        assertThat(found.getStatus()).isEqualTo(RunnerStatus.ACTIVE);
        assertThat(found.getDnfReason()).isNull();
        assertThat(found.getDnfYard()).isNull();
    }

    // ── CA11 — Persistance minimale de Passage avec scanned_at ──────────────
    @Test
    void ca11_persistPassageWithScannedAt() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA11", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca11"));
        Instant scannedAt = Instant.parse("2026-10-01T08:00:00Z");
        Passage passage = new Passage(runner, 1, PassageSource.SCAN, scannedAt);

        Passage saved = passageRepository.saveAndFlush(passage);

        assertThat(saved.getId()).isNotNull();
        flushAndClearPersistenceContext();
        Passage found = passageRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getRunner().getId()).isEqualTo(runner.getId());
        assertThat(found.getYardNumber()).isEqualTo(1);
        assertThat(found.getSource()).isEqualTo(PassageSource.SCAN);
        assertThat(found.getScannedAt()).isEqualTo(scannedAt);
    }

    // ── CA12 — Passage manuel avec scanned_at null ──────────────────────────
    @Test
    void ca12_manualPassageWithNullScannedAt() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA12", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca12"));
        Passage passage = new Passage(runner, 2, PassageSource.MANUAL, null);

        Passage saved = passageRepository.saveAndFlush(passage);

        flushAndClearPersistenceContext();
        Passage found = passageRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getYardNumber()).isEqualTo(2);
        assertThat(found.getScannedAt()).isNull();
        assertThat(found.getSource()).isEqualTo(PassageSource.MANUAL);
    }

    // ── CA13 — Unicité passage par coureur et yard ──────────────────────────
    @Test
    void ca13_duplicatePassageSameRunnerYardThrows() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA13", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca13"));
        passageRepository.saveAndFlush(new Passage(runner, 1, PassageSource.SCAN, Instant.now()));

        assertThatThrownBy(() ->
            passageRepository.saveAndFlush(new Passage(runner, 1, PassageSource.SCAN, Instant.now()))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── CA14 — Runner sans passage ──────────────────────────────────────────
    @Test
    void ca14_runnerWithNoPassages() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA14", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca14"));

        List<Passage> passages = passageRepository.findByRunnerId(runner.getId());
        assertThat(passages).isNotNull().isEmpty();
    }

    // ── CA15 — findByRaceId retourne tous les coureurs d'une course ──────────
    @Test
    void ca15_findByRaceIdReturnsAllRunners() {
        Race race1 = raceRepository.saveAndFlush(new Race("Race CA15-1", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Race race2 = raceRepository.saveAndFlush(new Race("Race CA15-2", LocalDate.of(2026, 10, 2), 6700, 3600, 0));
        runnerRepository.saveAndFlush(new Runner(race1, 1, "A", "tok-ca15-a"));
        runnerRepository.saveAndFlush(new Runner(race1, 2, "B", "tok-ca15-b"));
        runnerRepository.saveAndFlush(new Runner(race1, 3, "C", "tok-ca15-c"));
        runnerRepository.saveAndFlush(new Runner(race2, 1, "D", "tok-ca15-d"));

        List<Runner> runners = runnerRepository.findByRaceId(race1.getId());
        assertThat(runners).hasSize(3);
        assertThat(runners).allMatch(r -> r.getRace().getId().equals(race1.getId()));
    }

    // ── CA16 — findByQrToken retourne le bon coureur ─────────────────────────
    @Test
    void ca16_findByQrTokenReturnsCorrectRunner() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA16", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-unique-xyz"));

        Optional<Runner> found = runnerRepository.findByQrToken("tok-unique-xyz");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(runner.getId());
    }

    // ── CA17 — findByRaceIdAndStatus filtre par statut ───────────────────────
    @Test
    void ca17_findByRaceIdAndStatusFilters() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA17", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner active1 = runnerRepository.saveAndFlush(new Runner(race, 1, "A", "tok-ca17-a"));
        Runner active2 = runnerRepository.saveAndFlush(new Runner(race, 2, "B", "tok-ca17-b"));
        Runner dnfRunner = new Runner(race, 3, "C", "tok-ca17-c");
        dnfRunner.setStatus(RunnerStatus.DNF);
        dnfRunner.setDnfReason(DnfReason.TIMEOUT);
        dnfRunner.setDnfYard(1);
        runnerRepository.saveAndFlush(dnfRunner);

        List<Runner> actives = runnerRepository.findByRaceIdAndStatus(race.getId(), RunnerStatus.ACTIVE);
        assertThat(actives).hasSize(2);
    }

    // ── CA18 — findByStatus retourne les courses par statut ──────────────────
    @Test
    void ca18_findByStatusReturnsCorrectRaces() {
        raceRepository.saveAndFlush(new Race("Race CA18-S1", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        raceRepository.saveAndFlush(new Race("Race CA18-S2", LocalDate.of(2026, 10, 2), 6700, 3600, 0));
        Race running = new Race("Race CA18-R1", LocalDate.of(2026, 10, 3), 6700, 3600, 0);
        running.setStatus(RaceStatus.RUNNING);
        running.setStartedAt(Instant.now());
        raceRepository.saveAndFlush(running);

        List<Race> runningRaces = raceRepository.findByStatus(RaceStatus.RUNNING);
        assertThat(runningRaces).hasSize(1);
        assertThat(runningRaces.get(0).getStatus()).isEqualTo(RaceStatus.RUNNING);
    }

    // ── CA19 — findByRunnerIdAndYardNumber retourne le bon passage ────────────
    @Test
    void ca19_findByRunnerIdAndYardNumberReturnsCorrectPassage() {
        Race race = raceRepository.saveAndFlush(new Race("Race CA19", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Runner runner = runnerRepository.saveAndFlush(new Runner(race, 1, "Alice", "tok-ca19"));
        passageRepository.saveAndFlush(new Passage(runner, 3, PassageSource.SCAN, Instant.now()));
        passageRepository.saveAndFlush(new Passage(runner, 4, PassageSource.SCAN, Instant.now()));

        Optional<Passage> found = passageRepository.findByRunnerIdAndYardNumber(runner.getId(), 3);
        assertThat(found).isPresent();
        assertThat(found.get().getYardNumber()).isEqualTo(3);
    }

    // ── CA21 — Champ name vide rejeté pour Race ───────────────────────────────
    @Test
    void ca21_nullRaceNameThrows() {
        Race race = new Race(null, LocalDate.of(2026, 10, 1), 6700, 3600, 0);
        assertThatThrownBy(() -> raceRepository.saveAndFlush(race))
            .isInstanceOf(ConstraintViolationException.class)
            .satisfies(thrown -> assertThat(((ConstraintViolationException) thrown).getConstraintViolations())
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("name"));
    }

    // ── CA22 — loop_elevation à zéro autorisé ─────────────────────────────────
    @Test
    void ca22_loopElevationZeroAllowed() {
        Race race = new Race("Flat Race CA22", LocalDate.of(2026, 10, 1), 6700, 3600, 0);
        Race saved = raceRepository.saveAndFlush(race);

        flushAndClearPersistenceContext();
        Race found = raceRepository.findById(saved.getId()).orElseThrow();
        assertThat(found).isNotSameAs(saved);
        assertThat(found.getLoopElevation()).isZero();
    }

    // ── TECH (hors CA, INC1-TECH1) — Setters Race et Runner exercés (couverture JaCoCo)
    @Test
    void tech_raceAndRunnerSettersUpdateFields() {
        Race race1 = raceRepository.saveAndFlush(new Race("Race CA23-1", LocalDate.of(2026, 10, 1), 6700, 3600, 0));
        Race race2 = raceRepository.saveAndFlush(new Race("Race CA23-2", LocalDate.of(2026, 10, 2), 6700, 3600, 0));

        // Race setters
        race1.setName("Race CA23-1 Updated");
        race1.setRaceDate(LocalDate.of(2026, 11, 1));
        race1.setLoopDistance(5000);
        race1.setLoopDuration(7200);
        race1.setLoopElevation(200);
        Race savedRace = raceRepository.saveAndFlush(race1);

        assertThat(savedRace.getName()).isEqualTo("Race CA23-1 Updated");
        assertThat(savedRace.getRaceDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(savedRace.getLoopDistance()).isEqualTo(5000);
        assertThat(savedRace.getLoopDuration()).isEqualTo(7200);
        assertThat(savedRace.getLoopElevation()).isEqualTo(200);

        // Runner setters
        Runner runner = runnerRepository.saveAndFlush(new Runner(race1, 10, "InitName", "tok-ca23-init"));
        runner.setRace(race2);
        runner.setBib(20);
        runner.setName("UpdatedName");
        runner.setQrToken("tok-ca23-updated");
        Runner savedRunner = runnerRepository.saveAndFlush(runner);

        assertThat(savedRunner.getRace().getId()).isEqualTo(race2.getId());
        assertThat(savedRunner.getBib()).isEqualTo(20);
        assertThat(savedRunner.getName()).isEqualTo("UpdatedName");
        assertThat(savedRunner.getQrToken()).isEqualTo("tok-ca23-updated");
    }
}
